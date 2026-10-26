package io.github.likeelysia.formalagent.web;

import jakarta.servlet.FilterChain;import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 首轮只开放“笔记”给普通测试账号；会话、聊天、记忆、生词、上传、后台任务和账号管理
 * 尚未完成 owner 隔离，必须在服务端拒绝，而不是靠隐藏按钮。
 */
@Component
public class UserAccessFilter extends OncePerRequestFilter {

    private static final List<String> BLOCKED_PREFIXES = List.of(
            "/api/conversations",
            "/api/agent",
            "/api/memory",
            "/api/vocab",
            "/api/upload",
            "/api/ingest",
            "/api/knowledge/vectors",
            "/api/admin",
            "/study.html",
            "/vocab.html");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER"))
                && blocked(request.getRequestURI())) {
            if (request.getRequestURI().endsWith(".html")) {
                response.sendRedirect("/notes.html");
                return;
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"该功能尚未完成多账号隔离，当前测试账号不可用\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean blocked(String path) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")
                    || (prefix.endsWith(".html") && path.equals(prefix))) {
                return true;
            }
        }
        return false;
    }
}
