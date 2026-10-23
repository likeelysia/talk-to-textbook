package io.github.likeelysia.formalagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录入口防暴力尝试。
 *
 * <p>必须跑在 Spring Security 的登录过滤器之前:登录由 Security 处理,MVC 拦截器管不到。
 * 这里在链前检查是否被退避,在链后根据“失败跳转 / 成功跳转”记账;换新 Cookie 也绕不过
 * (键是 IP + 账号,不是会话)。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public LoginRateLimitFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        String username = request.getParameter("username");
        long wait = attempts.blockedForSeconds(ip, username);
        if (wait > 0) {
            response.sendRedirect(request.getContextPath() + "/login.html?locked=1");
            return;
        }

        chain.doFilter(request, response);

        String location = response.getHeader("Location");
        if (location == null) {
            return;
        }
        if (location.contains("/login.html?error")) {
            attempts.failure(ip, username);
        } else if (location.contains("/study.html")) {
            attempts.success(ip, username);
        }
    }
}
