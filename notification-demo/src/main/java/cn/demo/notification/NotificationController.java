package cn.demo.notification;

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
 * 提供支付事实登记、可靠通知回调和通知任务查询接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/notify")
public class NotificationController {
    private static final String SCENARIO = "NOTIFY";

    private final NotificationService service;
    private final TaskStore tasks;
    private final ReceiptService receipts;
    private final BusinessRepository business;
    private final Faults faults;

    public NotificationController(NotificationService service, TaskStore tasks, ReceiptService receipts,
            BusinessRepository business, Faults faults) {
        this.service = service;
        this.tasks = tasks;
        this.receipts = receipts;
        this.business = business;
        this.faults = faults;
    }

    /**
     * 原子登记支付事实及待通知任务。
     *
     * @param purchase
     *            已支付的业务请求
     * @return 订单支付状态和通知状态
     */
    @PostMapping("/payments")
    public Map<String, String> payment(@Valid @RequestBody Purchase purchase) {
        service.recordPayment(purchase);
        return Map.of("orderId", purchase.orderId(), "status", "PAID", "notification", "PENDING");
    }

    /**
     * 查询已登记的支付订单。
     *
     * @return 支付订单列表
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of("orders", business.orders(SCENARIO));
    }

    /**
     * 查询通知任务及其重试状态。
     *
     * @return 当前场景的通知任务
     */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return tasks.list(SCENARIO);
    }

    /**
     * 将指定通知任务重新置为待投递状态。
     *
     * @param id
     *            通知任务编号
     * @return 待处理状态
     */
    @PostMapping("/tasks/{id}/replay")
    public Map<String, String> replay(@PathVariable String id) {
        tasks.replay(SCENARIO, id);
        return Map.of("status", "PENDING");
    }

    /**
     * 幂等接收支付通知，可在接收前后注入故障验证重试行为。
     *
     * @param purchase
     *            支付通知内容
     * @return 回调处理回执
     */
    @PostMapping("/callback")
    public DeliveryWorker.Ack callback(@Valid @RequestBody Purchase purchase) {
        faults.before();
        String result = receipts.receive(SCENARIO, purchase);
        faults.after();
        return new DeliveryWorker.Ack(result);
    }

    /**
     * 查询指定通知的接收回执。
     *
     * @param id
     *            通知编号
     * @return 持久化回执
     */
    @GetMapping("/receipts/{id}")
    public Map<String, Object> receipt(@PathVariable String id) {
        return receipts.find(SCENARIO, id);
    }
}
