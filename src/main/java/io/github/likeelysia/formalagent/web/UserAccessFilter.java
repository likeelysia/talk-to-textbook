package io.github.likeelysia.formalagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** USER 显式允许矩阵：路径 + 方法 + Content-Type；未知 API 默认拒绝。 */
@Component
public class UserAccessFilter extends OncePerRequestFilter {

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
        if (path.startsWith("/api/") && !allowed(request, path)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"该功能尚未完成多账号隔离，当前测试账号不可用\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowed(HttpServletRequest r, String path) {
        String m = r.getMethod().toUpperCase(Locale.ROOT);
        if (path.equals("/api/auth/me") || path.equals("/api/auth/csrf")
                || path.equals("/api/health")) return "GET".equals(m);
        if (path.equals("/api/knowledge") || path.equals("/api/knowledge/count")
                || path.equals("/api/knowledge/page") || path.equals("/api/knowledge/item")) {
            return "GET".equals(m);
        }
        if (path.equals("/api/vocab/import")) {
            String type = r.getContentType();
            return "POST".equals(m) && type != null
                    && "multipart/form-data".equalsIgnoreCase(type.split(";", 2)[0].trim());
        }
        if (path.equals("/api/vocab/book/bulk")) {
            return "POST".equals(m) && isJson(r.getContentType());
        }
        if (path.equals("/api/vocab/book")) {
            return "GET".equals(m) || "POST".equals(m) && isJson(r.getContentType());
        }
        if (path.equals("/api/vocab/extract") || path.equals("/api/vocab/explain")
                || path.equals("/api/vocab/docx") || path.matches("/api/vocab/book/[A-Za-z0-9-]{1,40}/review")) {
            return "POST".equals(m) && isJson(r.getContentType());
        }
        if (path.matches("/api/vocab/book/[A-Za-z0-9-]{1,40}")) {
            return "DELETE".equals(m) || "PATCH".equals(m) && isJson(r.getContentType());
        }
        if (path.equals("/api/conversations")) return "GET".equals(m) || "POST".equals(m);
        if (path.matches("/api/conversations/[A-Za-z0-9_-]{1,40}")) {
            return "GET".equals(m) || "PATCH".equals(m) || "DELETE".equals(m);
        }
        if (path.matches("/api/conversations/[A-Za-z0-9_-]{1,40}/memory-scope")) {
            return "PUT".equals(m);
        }
        if (path.equals("/api/agent")) {
            String type = r.getContentType();
            boolean multipart = type != null
                    && "multipart/form-data".equalsIgnoreCase(type.split(";", 2)[0].trim());
            return "POST".equals(m) && (isJson(type) || multipart);
        }
        if (path.equals("/api/agent/stream")) {
            return "POST".equals(m) && isJson(r.getContentType());
        }
        if (path.equals("/api/memory")) return "GET".equals(m) || "POST".equals(m) || "DELETE".equals(m);
        if (path.equals("/api/memory/core")) return "GET".equals(m) || "PUT".equals(m);
        if (path.equals("/api/memory/conflicts") || path.equals("/api/memory/export")) {
            return "GET".equals(m);
        }
        if (path.matches("/api/memory/conflicts/[A-Za-z0-9_-]{1,40}")) return "DELETE".equals(m);
        if (path.matches("/api/memory/[A-Za-z0-9_-]{1,40}")) return "PATCH".equals(m) || "DELETE".equals(m);
        if (path.equals("/api/notes")) return "GET".equals(m) || "POST".equals(m);
        if (path.equals("/api/notes/manual")) return "POST".equals(m);
        if (path.equals("/api/notes/export")) return "GET".equals(m);
        if (path.matches("/api/notes/[A-Za-z0-9_-]{1,40}")) {
            return "GET".equals(m) || "PATCH".equals(m) || "DELETE".equals(m);
        }
        return false;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        int semi = contentType.indexOf(';');
        String type = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
        return "application/json".equalsIgnoreCase(type);
    }
}
