package cn.demo.at;
import org.apache.seata.core.context.RootContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class XidFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String xid=request.getHeader(RootContext.KEY_XID);
        String previous=RootContext.getXID();
        try {
            if(xid!=null && !xid.isBlank()) RootContext.bind(xid);
            chain.doFilter(request,response);
        } finally {
            // Servlet 工作线程会被复用，异常路径也必须清理，否则下一请求会加入旧事务。
            RootContext.unbind();if(previous!=null) RootContext.bind(previous);
        }
    }
}
