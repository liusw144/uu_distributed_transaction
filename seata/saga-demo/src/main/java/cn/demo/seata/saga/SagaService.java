package cn.demo.seata.saga;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.saga.engine.StateMachineEngine;
import io.seata.saga.engine.config.DbStateMachineConfig;
import io.seata.saga.engine.impl.ProcessCtrlStateMachineEngine;
import io.seata.saga.engine.store.db.DbAndReportTcStateLogStore;
import io.seata.saga.statelang.domain.ExecutionStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.sql.Connection;

@Service
public class SagaService {
    private static final String TENANT = "seata-saga-demo";
    private final DataSource mysql;
    private final ApplicationContext context;
    private final ObjectMapper json;
    private StateMachineEngine engine;

    public SagaService(@Qualifier("sagaMysql") DataSource mysql, ApplicationContext context, ObjectMapper json) throws Exception {
        this.mysql = mysql;
        this.context = context;
        this.json = json;
        rebuildEngine();
    }

    /**
     * 创建全新官方引擎；恢复不依赖旧对象内存或自建工作流表。
     */
    public synchronized void rebuildEngine() throws Exception {
        var config = new DbStateMachineConfig();
        config.setApplicationContext(context);
        config.setApplicationId(TENANT);
        config.setDefaultTenantId(TENANT);
        config.setDataSource(mysql);
        config.setTablePrefix("seata_saga_");
        config.setSagaJsonParser("jackson");
        config.setSagaBranchRegisterEnable(false);
        config.setResources(new String[]{"classpath:statelang/purchase.json"});
        // 官方日志存储对 null 模板有原生保护；显式安装它，跳过 DbConfig 自动创建 TC 模板。
        // 仍使用官方定义仓库、SQL 日志、序列、参数序列化、补偿栈与重放逻辑。
        var store = new DbAndReportTcStateLogStore();
        store.setDataSource(mysql);
        store.setTablePrefix("seata_saga_");
        store.setDbType("MySQL");
        store.setDefaultTenantId(TENANT);
        store.setSeqGenerator(config.getSeqGenerator());
        var serializer = new io.seata.saga.engine.serializer.impl.ParamsSerializer();
        serializer.setJsonParserName("jackson");
        store.setParamsSerializer(serializer);
        config.setStateLogStore(store);
        config.afterPropertiesSet();
        var fresh = new ProcessCtrlStateMachineEngine();
        fresh.setStateMachineConfig(config);
        engine = fresh;
    }

    public synchronized Map<String, Object> submit(Purchase p) {
        return withBusinessLock(p.orderId(), () -> {
            var params = params(p);
            String payload = params.get("payload").toString();
            var db = new JdbcTemplate(mysql);
            db.update("INSERT INTO seata_saga_requests(order_id,payload) VALUES (?,?) ON DUPLICATE KEY UPDATE order_id=order_id", p.orderId(), payload);
            String old = db.queryForObject("SELECT payload FROM seata_saga_requests WHERE order_id=?", String.class, p.orderId());
            if (!payload.equals(old)) throw new BusinessException("业务号已用于不同的请求内容");
            var previous = engine.getStateMachineConfig().getStateLogRepository().getStateMachineInstanceByBusinessKey(p.orderId(), TENANT);
            if (previous != null) return instance(previous.getId());
            var result = engine.startWithBusinessKey("seataSagaPurchase", TENANT, p.orderId(), new HashMap<>(Map.of("p", params)));
            return instance(result.getId());
        });
    }

    /**
     * 新建及恢复共用连接级业务锁，覆盖整个引擎执行，阻止跨 JVM 正向/补偿竞态。
     */
    private <T> T withBusinessLock(String businessKey, java.util.concurrent.Callable<T> action) {
        try (Connection c = mysql.getConnection()) {
            String lock = "sg:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(businessKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 60);
            try (var s = c.prepareStatement("SELECT GET_LOCK(?,5)")) {
                s.setString(1, lock);
                try (var r = s.executeQuery()) {
                    r.next();
                    if (r.getInt(1) != 1) throw new BusinessException("业务号正在处理中，请稍后重试");
                }
            }
            try {
                return action.call();
            } finally {
                try (var s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    s.setString(1, lock);
                    s.execute();
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Saga 执行失败，请按业务号查询数据库日志后诊断", e);
        }
    }

    private Map<String, Object> params(Purchase p) throws Exception {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("orderId", p.orderId());
        fields.put("userId", p.userId());
        fields.put("productId", p.productId());
        fields.put("quantity", p.quantity());
        fields.put("unitPrice", p.unitPrice().setScale(2).toPlainString());
        String payload = json.writeValueAsString(fields);
        fields.put("payload", payload);
        fields.put("amount", p.amount().toPlainString());
        fields.put("failAt", p.failAt().name());
        return fields;
    }

    public synchronized Map<String, Object> compensate(String id) {
        // 锁外读取仅用于定位锁名，绝不使用此处的状态决定能否开始补偿。
        var locator = engine.reloadStateMachineInstance(id);
        if (locator == null) throw new BusinessException("实例不存在");
        String key = locator.getBusinessKey();
        if (key == null || key.isBlank()) throw new BusinessException("实例缺少业务号，需人工诊断");
        return withBusinessLock(key, () -> {
            var inst = engine.reloadStateMachineInstance(id); // 等待正向执行完成后必须重新读取。
            if (inst == null || !key.equals(inst.getBusinessKey()) || !TENANT.equals(inst.getTenantId()))
                throw new BusinessException("实例不存在或业务号不匹配");
            if (inst.getStatus() == ExecutionStatus.SU)
                throw new BusinessException("成功订单不允许通过故障恢复接口取消");
            // 只重试已经启动并结束过的失败补偿；RU/无补偿记录并非失败补偿。
            // 首节点前强杀留下空日志时，直接交给引擎补偿会被错误归并为 SU。
            if (inst.isRunning() || inst.getStatus() == ExecutionStatus.RU || inst.getCompensationStatus() == null)
                throw new BusinessException("正向流程未完成或没有失败补偿记录，需专门恢复或人工诊断");
            if (inst.getStatus() != ExecutionStatus.FA && inst.getStatus() != ExecutionStatus.UN)
                throw new BusinessException("实例状态不支持失败补偿恢复");
            if (inst.getCompensationStatus() != ExecutionStatus.SU) engine.compensate(id, null);
            return instance(id);
        });
    }

    public synchronized Map<String, Object> instance(String id) {
        var inst = engine.reloadStateMachineInstance(id);
        if (inst == null) throw new BusinessException("实例不存在");
        var out = new LinkedHashMap<String, Object>();
        out.put("id", inst.getId());
        out.put("businessKey", inst.getBusinessKey());
        out.put("status", String.valueOf(inst.getStatus()));
        out.put("compensationStatus", inst.getCompensationStatus() == null ? "NONE" : inst.getCompensationStatus().name());
        out.put("running", inst.isRunning());
        out.put("states", new JdbcTemplate(mysql).queryForList("SELECT id,name,status,state_id_compensated_for,state_id_retried_for FROM seata_saga_state_inst WHERE machine_inst_id=? ORDER BY gmt_started,id", id));
        return out;
    }
}
