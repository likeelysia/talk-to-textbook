package io.github.likeelysia.formalagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * USER 采用显式允许清单：只有 B 阶段已经完成 owner 校验的 API 才放行，未知新 API 默认拒绝。
 * 管理员不受影响；图片 multipart、上传、背词、公共教材管理仍拒绝。
 */
@Component
public class UserAccessFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_API = Set.of(
            "/api/auth/me", "/api/auth/csrf", "/api/health",
            "/api/conversations", "/api/agent", "/api/agent/stream",
            "/api/memory", "/api/notes",
            "/api/knowledge", "/api/knowledge/count", "/api/knowledge/page",
            "/api/knowledge/item");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER"))) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        boolean api = path.startsWith("/api/");
        boolean allowed = !api || allowedApi(request, path);
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"该功能尚未完成多账号隔离，当前测试账号不可用\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedApi(HttpServletRequest request, String path) {
        if (path.equals("/api/agent")) {
            String ct = request.getContentType();
            return !"POST".equalsIgnoreCase(request.getMethod()) || (ct != null
                    && ct.toLowerCase().startsWith("application/json"));
        }
        if (ALLOWED_API.contains(path)) return true;
        for (String base : Set.of("/api/conversations", "/api/memory", "/api/notes")) {
            if (path.startsWith(base + "/")) return true;
        }
        return false;
    }
}
