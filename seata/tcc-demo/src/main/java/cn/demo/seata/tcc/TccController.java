package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 提供 TCC 下单、状态观察和二阶段故障注入接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/seata/tcc")
public class TccController {
    private final TccService service;
    private final TccStore store;
    private final PhaseFaults faults;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param service
     *            业务服务
     * @param store
     *            持久化分支存储
     * @param faults
     *            二阶段故障计数器
     */
    public TccController(TccService service, TccStore store, PhaseFaults faults) {
        this.service = service;
        this.store = store;
        this.faults = faults;
    }

    /**
     * 提交跨库购买请求。
     *
     * @param purchase
     *            购买请求
     * @return 提交结果
     */
    @PostMapping("/orders")
    public Object purchase(@Valid @RequestBody Purchase purchase) {
        return service.purchase(purchase);
    }

    /**
     * 查询各数据库当前状态；查询结果不保证同一时点。
     *
     * @return 各资源的观察状态
     */
    @GetMapping("/state")
    public Object state() {
        return store.snapshot();
    }

    /**
     * 设置二阶段故障并返回剩余次数。
     *
     * @param orderId
     *            业务订单编号
     * @param phase
     *            二阶段名称
     * @param count
     *            模拟故障次数
     * @return 故障注入配置
     */
    @PostMapping("/faults/{orderId}/{phase}/{count}")
    public Object fault(@PathVariable String orderId, @PathVariable String phase, @PathVariable int count) {
        faults.arm(orderId, phase, count);
        return Map.of("orderId", orderId, "phase", phase, "remaining", count);
    }
}
