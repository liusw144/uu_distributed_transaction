package cn.demo.common.workflow;

import static cn.demo.common.workflow.ProtocolParticipant.Resource.ACCOUNT;
import static cn.demo.common.workflow.ProtocolParticipant.Resource.STOCK;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import cn.demo.common.workflow.WorkflowStore.Flow;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实现教学协调器，持久化决议后持续沿确认或补偿方向恢复，不允许反悔。
 *
 * @author liusw144
 */
public class WorkflowEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_DRIVE_ITERATIONS = 6;
    private static final int FORWARD_STOCK_STEP = 1;
    private static final int FORWARD_ACCOUNT_STEP = 2;
    private static final String PROTOCOL_TCC = "TCC";
    private static final String PROTOCOL_SAGA = "SAGA";
    private static final String STATUS_PREPARING = "PREPARING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PREPARED = "PREPARED";
    private static final String STATUS_CONFIRMING = "CONFIRMING";
    private static final String STATUS_CANCELLING = "CANCELLING";
    private static final String STATUS_COMPENSATING = "COMPENSATING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_COMPENSATED = "COMPENSATED";
    private final WorkflowStore store;
    private final ProtocolParticipant participant;
    private final WorkflowFaults faults;
    private final int maxAttempts;
    private final int prepareTimeoutSeconds;
    private final String protocol;

    public WorkflowEngine(WorkflowStore store, ProtocolParticipant participant, WorkflowFaults faults, int maxAttempts,
            int prepareTimeoutSeconds) {
        if (maxAttempts < 1 || prepareTimeoutSeconds < 1
                || !Set.of(PROTOCOL_TCC, PROTOCOL_SAGA).contains(participant.protocol())) {
            throw new IllegalArgumentException("协调器配置非法");
        }
        this.store = store;
        this.participant = participant;
        this.faults = faults;
        this.maxAttempts = maxAttempts;
        this.prepareTimeoutSeconds = prepareTimeoutSeconds;
        this.protocol = participant.protocol();
    }

    /** 返回当前协调器使用的事务协议。 */
    public String protocol() {
        return protocol;
    }

    /** 读取当前协议下指定订单的协调状态。 */
    public Flow get(String id) {
        return store.require(id, protocol);
    }

    /** 幂等创建协调事务并驱动执行，TCC 可仅完成预留阶段。 */
    public Flow start(Purchase purchase, boolean prepareOnly) {
        if (prepareOnly && !PROTOCOL_TCC.equals(protocol)) {
            throw new BusinessException("Saga 不支持资源预留");
        }
        store.create(protocol, purchase, prepareOnly, prepareTimeoutSeconds);
        drive(purchase.orderId());
        return get(purchase.orderId());
    }

    /** 获取订单命名锁后驱动有限步状态转换。 */
    public void drive(String id) {
        store.withLock(id, () -> driveLocked(id));
    }

    private void driveLocked(String id) {
        for (int iteration = 0; iteration < MAX_DRIVE_ITERATIONS; iteration++) {
            Flow flow = get(id);
            if (WorkflowStore.TERMINAL.contains(flow.status()) || flow.blocked()) {
                return;
            }
            if (PROTOCOL_TCC.equals(protocol) && Set.of(STATUS_PREPARING, STATUS_PREPARED).contains(flow.status())
                    && flow.expired()) {
                store.transition(flow, STATUS_CANCELLING, "预留阶段超时，取消所有可能执行过的分支");
                continue;
            }
            switch (flow.status()) {
                case STATUS_PREPARING, STATUS_RUNNING -> {
                    prepare(flow);
                }
                case STATUS_PREPARED -> {
                    return;
                }
                case STATUS_CONFIRMING -> {
                    if (!secondPhase(flow, "confirm", () -> {
                        participant.confirm(STOCK, flow.purchase());
                        faults.after("confirm");
                        faults.before("confirm");
                        participant.confirm(ACCOUNT, flow.purchase());
                        faults.after("confirm");
                    }, STATUS_SUCCEEDED)) {
                        return;
                    }
                }
                case STATUS_CANCELLING, STATUS_COMPENSATING -> {
                    String phase = PROTOCOL_TCC.equals(protocol) ? "cancel" : "compensate";
                    // 即使正向结果未知也处理两个分支；空补偿墓碑会拦截迟到的正向请求。
                    if (!secondPhase(flow, phase, () -> {
                        participant.reverse(ACCOUNT, flow.purchase());
                        faults.after(phase);
                        faults.before(phase);
                        participant.reverse(STOCK, flow.purchase());
                        faults.after(phase);
                    }, PROTOCOL_TCC.equals(protocol) ? STATUS_CANCELLED : STATUS_COMPENSATED)) {
                        return;
                    }
                }
                default -> {
                    throw new IllegalStateException("未知协调状态：" + flow.status());
                }
            }
        }
    }

    private void prepare(Flow initial) {
        Flow flow = initial;
        Purchase purchase = flow.purchase();
        try {
            purchase.failIf(Purchase.Failure.AFTER_ORDER);
            if (flow.step() < FORWARD_STOCK_STEP) {
                participant.forward(STOCK, purchase);
                if (!store.advance(flow, FORWARD_STOCK_STEP)) {
                    return;
                }
                flow = get(flow.id());
                if (!initial.status().equals(flow.status())) {
                    return;
                }
            }
            purchase.failIf(Purchase.Failure.AFTER_STOCK);
            if (flow.step() < FORWARD_ACCOUNT_STEP) {
                participant.forward(ACCOUNT, purchase);
                if (!store.advance(flow, FORWARD_ACCOUNT_STEP)) {
                    return;
                }
                flow = get(flow.id());
                if (!initial.status().equals(flow.status())) {
                    return;
                }
            }
            purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
            flow = get(flow.id());
            if (!initial.status().equals(flow.status())) {
                return;
            }
            if (PROTOCOL_TCC.equals(protocol)) {
                // Try 期间也可能跨过截止时间；决议前重新读取，而不是仅在进入阶段时检查。
                flow = get(flow.id());
                if (!initial.status().equals(flow.status())) {
                    return;
                }
                if (flow.expired()) {
                    store.transition(flow, STATUS_CANCELLING, "Try 完成时预留已到期");
                } else {
                    store.transition(flow, flow.manualCommit() ? STATUS_PREPARED : STATUS_CONFIRMING, null);
                }
            } else {
                store.complete(flow, STATUS_SUCCEEDED);
            }
        } catch (Exception exception) {
            // 正向动作失败/超时视为结果未知，先持久化撤销决议，再补偿所有可能成功过的分支。
            Flow current = get(flow.id());
            if (Set.of(STATUS_PREPARING, STATUS_RUNNING).contains(current.status())) {
                store.transition(current, PROTOCOL_TCC.equals(protocol) ? STATUS_CANCELLING : STATUS_COMPENSATING,
                        exception.toString());
            } else {
                throw exception;
            }
        }
    }

    private boolean secondPhase(Flow flow, String phase, Runnable operations, String terminal) {
        try {
            faults.before(phase);
            operations.run();
            store.complete(flow, terminal);
            return true;
        } catch (Exception exception) {
            store.defer(flow, exception, maxAttempts);
            LOGGER.warn("{} {} 待恢复，阶段 {}", protocol, flow.id(), flow.status(), exception);
            return false;
        }
    }

    /** 对未过期的 TCC 预留持久化确认决议，并继续驱动二阶段。 */
    public Flow confirm(String id) {
        tccOnly();
        command(id, () -> {
            Flow flow = get(id);
            if (flow.expired() && STATUS_PREPARED.equals(flow.status())) {
                store.transition(flow, STATUS_CANCELLING, "预留已超时");
                driveLocked(id);
                throw new BusinessException("预留已到期，不能确认");
            }
            if (STATUS_PREPARED.equals(flow.status())) {
                store.transition(flow, STATUS_CONFIRMING, null);
            } else if (!Set.of(STATUS_CONFIRMING, STATUS_SUCCEEDED).contains(flow.status())) {
                throw new BusinessException("当前状态不能确认：" + flow.status());
            }
            driveLocked(id);
        });
        return get(id);
    }

    /** 在确认决议尚未产生时取消 TCC 事务。 */
    public Flow cancel(String id) {
        tccOnly();
        command(id, () -> {
            Flow flow = get(id);
            if (Set.of(STATUS_CONFIRMING, STATUS_SUCCEEDED).contains(flow.status())) {
                throw new BusinessException("确认决议已持久化，不能改为取消");
            }
            if (Set.of(STATUS_PREPARING, STATUS_PREPARED).contains(flow.status())) {
                store.transition(flow, STATUS_CANCELLING, "用户取消");
            } else if (!Set.of(STATUS_CANCELLING, STATUS_CANCELLED).contains(flow.status())) {
                throw new BusinessException("当前状态不能取消：" + flow.status());
            }
            driveLocked(id);
        });
        return get(id);
    }

    /** 重置未完成事务的恢复次数，并继续执行既有决议。 */
    public Flow retry(String id) {
        command(id, () -> {
            Flow flow = get(id);
            if (!WorkflowStore.TERMINAL.contains(flow.status())) {
                store.reset(flow);
            }
            driveLocked(id);
        });
        return get(id);
    }

    private void command(String id, Runnable action) {
        if (!store.withLock(id, action)) {
            throw new BusinessException("协调事务正在处理，请稍后重试");
        }
    }

    private void tccOnly() {
        if (!PROTOCOL_TCC.equals(protocol)) {
            throw new BusinessException("该命令仅适用于 TCC");
        }
    }

    /** 逐个恢复到期事务，单个事务失败不影响其他事务恢复。 */
    public void recover() {
        for (String id : store.due(protocol)) {
            try {
                drive(id);
            } catch (Exception exception) {
                LOGGER.warn("恢复协调事务 {} 失败", id, exception);
            }
        }
    }
}
