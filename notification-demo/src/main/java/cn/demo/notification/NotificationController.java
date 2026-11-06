package cn.demo.notification;
import cn.demo.common.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/notify")
public class NotificationController {
    private final NotificationService service;private final TaskStore tasks;private final ReceiptService receipts;
    private final BusinessRepository business;private final Faults faults;
    public NotificationController(NotificationService service,TaskStore tasks,ReceiptService receipts,BusinessRepository business,Faults faults) {
        this.service=service;this.tasks=tasks;this.receipts=receipts;this.business=business;this.faults=faults;
    }
    @PostMapping("/payments") public Map<String,String> payment(@Valid @RequestBody Purchase p) { service.recordPayment(p);return Map.of("orderId",p.orderId(),"status","PAID","notification","PENDING"); }
    @GetMapping("/state") public Map<String,Object> state() { return Map.of("orders",business.orders("NOTIFY")); }
    @GetMapping("/tasks") public List<Map<String,Object>> tasks() { return tasks.list("NOTIFY"); }
    @PostMapping("/tasks/{id}/replay") public Map<String,String> replay(@PathVariable String id) { tasks.replay("NOTIFY",id);return Map.of("status","PENDING"); }
    @PostMapping("/callback") public DeliveryWorker.Ack callback(@Valid @RequestBody Purchase p) {
        faults.before();String result=receipts.receive("NOTIFY",p);faults.after();return new DeliveryWorker.Ack(result);
    }
    @GetMapping("/receipts/{id}") public Map<String,Object> receipt(@PathVariable String id) { return receipts.find("NOTIFY",id); }
}
