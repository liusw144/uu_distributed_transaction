package cn.demo.outbox;
import cn.demo.common.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/outbox")
public class OutboxController {
    private final OutboxService service;private final BusinessRepository business;private final TaskStore tasks;
    private final ReceiptService receipts;private final Faults faults;
    public OutboxController(OutboxService service,BusinessRepository business,TaskStore tasks,ReceiptService receipts,Faults faults) {
        this.service=service;this.business=business;this.tasks=tasks;this.receipts=receipts;this.faults=faults;
    }
    @PostMapping("/orders") public Map<String,String> purchase(@Valid @RequestBody Purchase p) { service.purchase(p);return Map.of("orderId",p.orderId(),"status","PENDING"); }
    @GetMapping("/state") public Map<String,Object> state() { return business.snapshot("OUTBOX"); }
    @GetMapping("/tasks") public List<Map<String,Object>> tasks() { return tasks.list("OUTBOX"); }
    @PostMapping("/tasks/{id}/replay") public Map<String,String> replay(@PathVariable String id) { tasks.replay("OUTBOX",id);return Map.of("status","PENDING"); }
    @PostMapping("/consume") public DeliveryWorker.Ack consume(@Valid @RequestBody Purchase p) {
        faults.before();String result=receipts.receive("OUTBOX",p);faults.after();return new DeliveryWorker.Ack(result);
    }
    @GetMapping("/receipts/{id}") public Map<String,Object> receipt(@PathVariable String id) { return receipts.find("OUTBOX",id); }
}
