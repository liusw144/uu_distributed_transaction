package cn.demo.outbox;

import cn.demo.common.BusinessRepository;
import cn.demo.common.DeliveryWorker;
import cn.demo.common.Faults;
import cn.demo.common.Purchase;
import cn.demo.common.ReceiptService;
import cn.demo.common.TaskStore;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供本地消息表下单、消息消费和投递任务管理接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/outbox")
public class OutboxController {
    private static final String SCENARIO = "OUTBOX";

    private final OutboxService service;
    private final BusinessRepository business;
    private final TaskStore tasks;
    private final ReceiptService receipts;
    private final Faults faults;

    public OutboxController(OutboxService service, BusinessRepository business, TaskStore tasks,
            ReceiptService receipts, Faults faults) {
        this.service = service;
        this.business = business;
        this.tasks = tasks;
        this.receipts = receipts;
        this.faults = faults;
    }

    /**
     * 提交本地下单事务并返回等待异步扣款的订单。
     *
     * @param purchase
     *            下单请求
     * @return 订单编号和待处理状态
     */
    @PostMapping("/orders")
    public Map<String, String> purchase(@Valid @RequestBody Purchase purchase) {
        service.purchase(purchase);
        return Map.of("orderId", purchase.orderId(), "status", "PENDING");
    }

    /**
     * 查询订单、库存和账户的业务状态。
     *
     * @return 业务状态快照
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return business.snapshot(SCENARIO);
    }

    /**
     * 查询消息投递任务及其重试状态。
     *
     * @return 当前场景的投递任务
     */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return tasks.list(SCENARIO);
    }

    /**
     * 将指定任务重新置为待投递状态。
     *
     * @param id
     *            任务编号
     * @return 待处理状态
     */
    @PostMapping("/tasks/{id}/replay")
    public Map<String, String> replay(@PathVariable String id) {
        tasks.replay(SCENARIO, id);
        return Map.of("status", "PENDING");
    }

    /**
     * 幂等接收订单事件并扣款，可在处理前后注入故障以验证重试。
     *
     * @param purchase
     *            投递的订单事件
     * @return 消费回执
     */
    @PostMapping("/consume")
    public DeliveryWorker.Ack consume(@Valid @RequestBody Purchase purchase) {
        faults.before();
        String result = receipts.receive(SCENARIO, purchase);
        faults.after();
        return new DeliveryWorker.Ack(result);
    }

    /**
     * 查询指定消息的消费回执。
     *
     * @param id
     *            消息编号
     * @return 持久化回执
     */
    @GetMapping("/receipts/{id}")
    public Map<String, Object> receipt(@PathVariable String id) {
        return receipts.find(SCENARIO, id);
    }
}
