package cn.demo.seata.saga;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.saga.engine.StateMachineEngine;
import io.seata.saga.engine.config.DbStateMachineConfig;
import io.seata.saga.engine.impl.ProcessCtrlStateMachineEngine;
import io.seata.saga.engine.serializer.impl.ParamsSerializer;
import io.seata.saga.engine.store.db.DbAndReportTcStateLogStore;
import io.seata.saga.statelang.domain.ExecutionStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;

/**
 * 通过官方持久化状态机和业务号锁执行、查询及恢复 Saga。
 *
 * @author liusw144
 */
@Service
public class SagaService {
    private static final String TENANT = "seata-saga-demo";
    private final DataSource mysqlDataSource;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private StateMachineEngine engine;

    /**
     * 注入持久化数据源和序列化器并初始化官方状态机引擎。
     *
     * @param mysqlDataSource
     *            MySQL 数据源
     * @param applicationContext
     *            Spring 应用上下文
     * @param objectMapper
     *            业务参数序列化器
     * @throws Exception
     *             状态机初始化失败
     */
    public SagaService(@Qualifier("sagaMysql") DataSource mysqlDataSource, ApplicationContext applicationContext,
            ObjectMapper objectMapper) throws Exception {
        this.mysqlDataSource = mysqlDataSource;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        rebuildEngine();
    }

    /**
     * 创建全新官方引擎；恢复不依赖旧对象内存或自建工作流表。
     */
    public synchronized void rebuildEngine() throws Exception {
        DbStateMachineConfig config = new DbStateMachineConfig();
        config.setApplicationContext(applicationContext);
        config.setApplicationId(TENANT);
        config.setDefaultTenantId(TENANT);
        config.setDataSource(mysqlDataSource);
        config.setTablePrefix("seata_saga_");
        config.setSagaJsonParser("jackson");
        config.setSagaBranchRegisterEnable(false);
        config.setResources(new String[]{"classpath:statelang/purchase.json"});
        // 官方日志存储对 null 模板有原生保护；显式安装它，跳过 DbConfig 自动创建 TC 模板。
        // 仍使用官方定义仓库、SQL 日志、序列、参数序列化、补偿栈与重放逻辑。
        DbAndReportTcStateLogStore store = new DbAndReportTcStateLogStore();
        store.setDataSource(mysqlDataSource);
        store.setTablePrefix("seata_saga_");
        store.setDbType("MySQL");
        store.setDefaultTenantId(TENANT);
        store.setSeqGenerator(config.getSeqGenerator());
        ParamsSerializer serializer = new ParamsSerializer();
        serializer.setJsonParserName("jackson");
        store.setParamsSerializer(serializer);
        config.setStateLogStore(store);
        config.afterPropertiesSet();
        ProcessCtrlStateMachineEngine freshEngine = new ProcessCtrlStateMachineEngine();
        freshEngine.setStateMachineConfig(config);
        engine = freshEngine;
    }

    /**
     * 提交购买请求，相同业务号返回原状态机实例。
     *
     * @param purchase
     *            购买请求
     * @return 状态机实例信息
     */
    public synchronized Map<String, Object> submit(Purchase purchase) {
        return withBusinessLock(purchase.orderId(), () -> {
            Map<String, Object> parameters = params(purchase);
            String payload = parameters.get("payload").toString();
            JdbcTemplate jdbcTemplate = new JdbcTemplate(mysqlDataSource);
            jdbcTemplate.update("INSERT INTO seata_saga_requests(order_id,payload) VALUES (?,?) "
                    + "ON DUPLICATE KEY UPDATE order_id=order_id", purchase.orderId(), payload);
            String previousPayload = jdbcTemplate.queryForObject(
                    "SELECT payload FROM seata_saga_requests WHERE order_id=?", String.class, purchase.orderId());
            if (!payload.equals(previousPayload)) {
                throw new BusinessException("业务号已用于不同的请求内容");
            }
            var previous = engine.getStateMachineConfig().getStateLogRepository()
                    .getStateMachineInstanceByBusinessKey(purchase.orderId(), TENANT);
            if (previous != null) {
                return instance(previous.getId());
            }
            var result = engine.startWithBusinessKey("seataSagaPurchase", TENANT, purchase.orderId(),
                    new HashMap<>(Map.of("p", parameters)));
            return instance(result.getId());
        });
    }

    /**
     * 新建及恢复共用连接级业务锁，覆盖整个引擎执行，阻止跨 JVM 正向/补偿竞态。
     */
    private <T> T withBusinessLock(String businessKey, Callable<T> action) {
        try (Connection connection = mysqlDataSource.getConnection()) {
            String lockName = "sg:" + HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(businessKey.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 60);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,5)")) {
                statement.setString(1, lockName);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) != 1) {
                        throw new BusinessException("业务号正在处理中，请稍后重试");
                    }
                }
            }
            try {
                return action.call();
            } finally {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lockName);
                    statement.execute();
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Saga 执行失败，请按业务号查询数据库日志后诊断", exception);
        }
    }

    private Map<String, Object> params(Purchase purchase) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", purchase.orderId());
        fields.put("userId", purchase.userId());
        fields.put("productId", purchase.productId());
        fields.put("quantity", purchase.quantity());
        fields.put("unitPrice", purchase.unitPrice().setScale(2).toPlainString());
        String payload = objectMapper.writeValueAsString(fields);
        fields.put("payload", payload);
        fields.put("amount", purchase.amount().toPlainString());
        fields.put("failAt", purchase.failAt().name());
        return fields;
    }

    /**
     * 在业务号锁内重新读取实例并重试失败补偿。
     *
     * @param id
     *            状态机实例编号
     * @return 补偿后的状态机实例信息
     */
    public synchronized Map<String, Object> compensate(String id) {
        // 锁外读取仅用于定位锁名，绝不使用此处的状态决定能否开始补偿。
        var locator = engine.reloadStateMachineInstance(id);
        if (locator == null) {
            throw new BusinessException("实例不存在");
        }
        String businessKey = locator.getBusinessKey();
        if (businessKey == null || businessKey.isBlank()) {
            throw new BusinessException("实例缺少业务号，需人工诊断");
        }
        return withBusinessLock(businessKey, () -> {
            var stateMachineInstance = engine.reloadStateMachineInstance(id); // 等待正向执行完成后必须重新读取。
            if (stateMachineInstance == null || !businessKey.equals(stateMachineInstance.getBusinessKey())
                    || !TENANT.equals(stateMachineInstance.getTenantId())) {
                throw new BusinessException("实例不存在或业务号不匹配");
            }
            if (stateMachineInstance.getStatus() == ExecutionStatus.SU) {
                throw new BusinessException("成功订单不允许通过故障恢复接口取消");
            }
            // 只重试已经启动并结束过的失败补偿；RU/无补偿记录并非失败补偿。
            // 首节点前强杀留下空日志时，直接交给引擎补偿会被错误归并为 SU。
            if (stateMachineInstance.isRunning() || stateMachineInstance.getStatus() == ExecutionStatus.RU
                    || stateMachineInstance.getCompensationStatus() == null) {
                throw new BusinessException("正向流程未完成或没有失败补偿记录，需专门恢复或人工诊断");
            }
            if (stateMachineInstance.getStatus() != ExecutionStatus.FA
                    && stateMachineInstance.getStatus() != ExecutionStatus.UN) {
                throw new BusinessException("实例状态不支持失败补偿恢复");
            }
            if (stateMachineInstance.getCompensationStatus() != ExecutionStatus.SU) {
                engine.compensate(id, null);
            }
            return instance(id);
        });
    }

    /**
     * 读取状态机实例与持久化节点日志。
     *
     * @param id
     *            状态机实例编号
     * @return 状态机实例信息
     */
    public synchronized Map<String, Object> instance(String id) {
        var stateMachineInstance = engine.reloadStateMachineInstance(id);
        if (stateMachineInstance == null) {
            throw new BusinessException("实例不存在");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", stateMachineInstance.getId());
        response.put("businessKey", stateMachineInstance.getBusinessKey());
        response.put("status", String.valueOf(stateMachineInstance.getStatus()));
        response.put("compensationStatus",
                stateMachineInstance.getCompensationStatus() == null
                        ? "NONE"
                        : stateMachineInstance.getCompensationStatus().name());
        response.put("running", stateMachineInstance.isRunning());
        response.put("states",
                new JdbcTemplate(mysqlDataSource)
                        .queryForList(
                                "SELECT id,name,status,state_id_compensated_for,state_id_retried_for "
                                        + "FROM seata_saga_state_inst WHERE machine_inst_id=? ORDER BY gmt_started,id",
                                id));
        return response;
    }
}
