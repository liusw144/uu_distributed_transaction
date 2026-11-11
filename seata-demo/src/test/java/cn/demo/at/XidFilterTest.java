package cn.demo.at;

import io.seata.core.context.RootContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.ServletException;
import static org.junit.jupiter.api.Assertions.*;
class XidFilterTest {
    @Test void failedRemoteBranchDoesNotLeakXidToNextRequest() {
        var request=new MockHttpServletRequest();request.addHeader(RootContext.KEY_XID,"127.0.0.1:8091:1234");
        var filter=new XidFilter();
        assertThrows(ServletException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            assertEquals("127.0.0.1:8091:1234",RootContext.getXID());throw new ServletException("模拟异常");
        }));
        assertNull(RootContext.getXID());
    }
}
