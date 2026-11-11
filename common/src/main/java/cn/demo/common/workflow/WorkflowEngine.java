package cn.demo.common.workflow;

import cn.demo.common.*;
import org.slf4j.*;

import static cn.demo.common.workflow.ProtocolParticipant.Resource.*;

import cn.demo.common.workflow.WorkflowStore.Flow;

import java.util.Set;

/**
 * 手写教学协调器：决议持久化后不可反悔。失败的确认/补偿必须继续原方向恢复。
 */
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private final WorkflowStore store;
    private final ProtocolParticipant participant;
    private final WorkflowFaults faults;
    private final int maxAttempts, prepareTimeoutSeconds;
    private final String protocol;

    public WorkflowEngine(WorkflowStore store, ProtocolParticipant participant, WorkflowFaults faults,
                          int maxAttempts, int prepareTimeoutSeconds) {
        if (maxAttempts < 1 || prepareTimeoutSeconds < 1 || !Set.of("TCC", "SAGA").contains(participant.protocol()))
            throw new IllegalArgumentException("协调器配置非法");
        this.store = store;
        this.participant = participant;
        this.faults = faults;
        this.maxAttempts = maxAttempts;
        this.prepareTimeoutSeconds = prepareTimeoutSeconds;
        this.protocol = participant.protocol();
    }

    public String protocol() {
        return protocol;
    }

    public Flow get(String id) {
        return store.require(id, protocol);
    }

    public Flow start(Purchase p, boolean prepareOnly) {
        if (prepareOnly && !"TCC".equals(protocol)) throw new BusinessException("Saga 不支持资源预留");
        store.create(protocol, p, prepareOnly, prepareTimeoutSeconds);
        drive(p.orderId());
        return get(p.orderId());
    }

    public void drive(String id) {
        store.withLock(id, () -> driveLocked(id));
    }

    private void driveLocked(String id) {
        for (int i = 0; i < 6; i++) {
            Flow f = get(id);
            if (WorkflowStore.TERMINAL.contains(f.status()) || f.blocked()) return;
            if ("TCC".equals(protocol) && Set.of("PREPARING", "PREPARED").contains(f.status()) && f.expired()) {
                store.transition(f, "CANCELLING", "预留阶段超时，取消所有可能执行过的分支");
                continue;
            }
            switch (f.status()) {
                case "PREPARING", "RUNNING" -> prepare(f);
                case "PREPARED" -> {
                    return;
                }
                case "CONFIRMING" -> {
                    if (!secondPhase(f, "confirm", () -> {
                        participant.confirm(STOCK, f.purchase());
                        faults.after("confirm");
                        faults.before("confirm");
                        participant.confirm(ACCOUNT, f.purchase());
                        faults.after("confirm");
                    }, "SUCCEEDED")) return;
                }
                case "CANCELLING", "COMPENSATING" -> {
                    String phase = "TCC".equals(protocol) ? "cancel" : "compensate";
                    // 即使正向结果未知也处理两个分支；空补偿墓碑会拦截迟到的正向请求。
                    if (!secondPhase(f, phase, () -> {
                        participant.reverse(ACCOUNT, f.purchase());
                        faults.after(phase);
                        faults.before(phase);
                        participant.reverse(STOCK, f.purchase());
                        faults.after(phase);
                    }, "TCC".equals(protocol) ? "CANCELLED" : "COMPENSATED")) return;
                }
                default -> throw new IllegalStateException("未知协调状态：" + f.status());
            }
        }
    }

    private void prepare(Flow initial) {
        Flow f = initial;
        Purchase p = f.purchase();
        try {
            p.failIf(Purchase.Failure.AFTER_ORDER);
            if (f.step() < 1) {
                participant.forward(STOCK, p);
                if (!store.advance(f, 1)) return;
                f = get(f.id());
                if (!initial.status().equals(f.status())) return;
            }
            p.failIf(Purchase.Failure.AFTER_STOCK);
            if (f.step() < 2) {
                participant.forward(ACCOUNT, p);
                if (!store.advance(f, 2)) return;
                f = get(f.id());
                if (!initial.status().equals(f.status())) return;
            }
            p.failIf(Purchase.Failure.AFTER_ACCOUNT);
            f = get(f.id());
            if (!initial.status().equals(f.status())) return;
            if ("TCC".equals(protocol)) {
                // Try 期间也可能跨过截止时间；决议前重新读取，而不是仅在进入阶段时检查。
                f = get(f.id());
                if (!initial.status().equals(f.status())) return;
                if (f.expired()) store.transition(f, "CANCELLING", "Try 完成时预留已到期");
                else store.transition(f, f.manualCommit() ? "PREPARED" : "CONFIRMING", null);
            } else store.complete(f, "SUCCEEDED");
        } catch (Exception e) {
            // 正向动作失败/超时视为结果未知，先持久化撤销决议，再补偿所有可能成功过的分支。
            Flow current = get(f.id());
            if (Set.of("PREPARING", "RUNNING").contains(current.status()))
                store.transition(current, "TCC".equals(protocol) ? "CANCELLING" : "COMPENSATING", e.toString());
            else throw e;
        }
    }

    private boolean secondPhase(Flow f, String phase, Runnable operations, String terminal) {
        try {
            faults.before(phase);
            operations.run();
            store.complete(f, terminal);
            return true;
        } catch (Exception e) {
            store.defer(f, e, maxAttempts);
            log.warn("{} {} 待恢复，阶段 {}：{}", protocol, f.id(), f.status(), e.toString());
            return false;
        }
    }

    public Flow confirm(String id) {
        tccOnly();
        command(id, () -> {
            Flow f = get(id);
            if (f.expired() && "PREPARED".equals(f.status())) {
                store.transition(f, "CANCELLING", "预留已超时");
                driveLocked(id);
                throw new BusinessException("预留已到期，不能确认");
            }
            if ("PREPARED".equals(f.status())) store.transition(f, "CONFIRMING", null);
            else if (!Set.of("CONFIRMING", "SUCCEEDED").contains(f.status()))
                throw new BusinessException("当前状态不能确认：" + f.status());
            driveLocked(id);
        });
        return get(id);
    }

    public Flow cancel(String id) {
        tccOnly();
        command(id, () -> {
            Flow f = get(id);
            if (Set.of("CONFIRMING", "SUCCEEDED").contains(f.status()))
                throw new BusinessException("确认决议已持久化，不能改为取消");
            if (Set.of("PREPARING", "PREPARED").contains(f.status()))
                store.transition(f, "CANCELLING", "用户取消");
            else if (!Set.of("CANCELLING", "CANCELLED").contains(f.status()))
                throw new BusinessException("当前状态不能取消：" + f.status());
            driveLocked(id);
        });
        return get(id);
    }

    public Flow retry(String id) {
        command(id, () -> {
            Flow f = get(id);
            if (!WorkflowStore.TERMINAL.contains(f.status())) store.reset(f);
            driveLocked(id);
        });
        return get(id);
    }

    private void command(String id, Runnable action) {
        if (!store.withLock(id, action)) throw new BusinessException("协调事务正在处理，请稍后重试");
    }

    private void tccOnly() {
        if (!"TCC".equals(protocol)) throw new BusinessException("该命令仅适用于 TCC");
    }

    public void recover() {
        for (String id : store.due(protocol)) {
            try {
                drive(id);
            } catch (Exception e) {
                log.warn("恢复协调事务 {} 失败：{}", id, e.toString());
            }
        }
    }
}
