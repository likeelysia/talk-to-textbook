package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.account.Account;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.account.AccountUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个已登录请求重新核对账号状态和 session_version。
 * 停用/重置密码后，旧的 HttpSession 下一次请求即失效，而不是只影响下一次登录。
 */
@Component
public class AccountStatusFilter extends OncePerRequestFilter {

    public static final String SESSION_VERSION = "FA_SESSION_VERSION";

    private final AccountService accounts;

    public AccountStatusFilter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/login") || path.equals("/logout")
                || path.equals("/login.html") || path.equals("/api/health")
                || path.equals("/api/auth/csrf");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())
                || !(auth.getPrincipal() instanceof AccountUserDetails details)) {
            chain.doFilter(request, response);
            return;
        }

        Account current;
        try {
            current = accounts.requireByUsername(auth.getName());
        } catch (RuntimeException e) {
            reject(request, response, "账号不存在或已失效");
            return;
        }
        HttpSession session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(SESSION_VERSION);
        if (!current.enabled() || (stored != null
                && Long.parseLong(String.valueOf(stored)) != current.sessionVersion())) {
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            reject(request, response, "账号已停用或会话已失效,请重新登录");
            return;
        }
        if (stored == null && session != null) {
            session.setAttribute(SESSION_VERSION, current.sessionVersion());
        }
        // 让后续 Controller 看到的是当前角色/账号，而不是登录时缓存的旧对象。
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + message + "\"}");
        } else {
            response.sendRedirect("/login.html?expired");
        }
    }
}
