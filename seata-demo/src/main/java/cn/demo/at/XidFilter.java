package cn.demo.at;

import io.seata.core.context.RootContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 HTTP 头绑定 Seata XID，并在请求结束后恢复线程原有的事务上下文。
 *
 * @author liusw144
 */
@Component
public class XidFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String xid = request.getHeader(RootContext.KEY_XID);
        String previousXid = RootContext.getXID();
        try {
            if (xid != null && !xid.isBlank()) {
                RootContext.bind(xid);
            }
            chain.doFilter(request, response);
        } finally {
            // Servlet 工作线程会被复用，异常路径也必须清理，否则下一请求会加入旧事务。
            RootContext.unbind();
            if (previousXid != null) {
                RootContext.bind(previousXid);
            }
        }
    }
}
