package cn.demo.common;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅用于本地故障演示；重启会清零，业务幂等状态仍保存在数据库。 */
@Component
public class Faults {
    private final AtomicInteger fail=new AtomicInteger(),loseAck=new AtomicInteger();
    private boolean take(AtomicInteger counter) { return counter.getAndUpdate(n->Math.max(0,n-1))>0; }
    public void before() { if(take(fail)) throw new BusinessException("模拟接收端暂时不可用"); }
    public void after() { if(take(loseAck)) throw new BusinessException("模拟已提交但 ACK 丢失"); }
    public void set(int failures,int lostAcks) {
        if(failures<0 || lostAcks<0) throw new IllegalArgumentException();
        fail.set(failures); loseAck.set(lostAcks);
    }
    @RestController public static class Controller {
        private final Faults faults;
        public Controller(Faults faults) { this.faults=faults; }
        @PostMapping("/demo/faults") public Map<String,Object> set(@RequestParam(defaultValue="0") int failNext,@RequestParam(defaultValue="0") int loseAckNext) {
            faults.set(failNext,loseAckNext); return Map.of("failNext",failNext,"loseAckNext",loseAckNext);
        }
        @GetMapping("/demo/health") public Map<String,String> health() { return Map.of("status","UP"); }
    }
}
