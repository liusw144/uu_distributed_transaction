package cn.demo.at;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.seata.core.context.RootContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证XidFilter的事务行为与边界条件。
 *
 * @author liusw144
 */
class XidFilterTest {

    @Test
    void failedRemoteBranchDoesNotLeakXidToNextRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader(RootContext.KEY_XID, "127.0.0.1:8091:1234");
        var filter = new XidFilter();
        assertThrows(ServletException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    assertEquals("127.0.0.1:8091:1234", RootContext.getXID());
                    throw new ServletException("模拟异常");
                }));
        assertNull(RootContext.getXID());
    }
}
