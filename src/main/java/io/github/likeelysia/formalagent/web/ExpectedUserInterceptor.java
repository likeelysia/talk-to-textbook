package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.account.AccountUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 私有写请求必须携带页面确认过的预期 userId；进入 Controller 前拒绝不一致。 */
@Component
public class ExpectedUserInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Expected-User-Id";
    private static final Set<String> PRIVATE_WRITE_PREFIXES = Set.of(
            "/api/conversations", "/api/memory", "/api/notes", "/api/agent");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String method = request.getMethod();
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method)) return true;
        String path = request.getRequestURI();
        if (PRIVATE_WRITE_PREFIXES.stream().noneMatch(path::startsWith)) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AccountUserDetails details)) {
            return true;
        }
        String expected = request.getHeader(HEADER);
        if (expected != null && expected.equals(details.userId())) return true;
        response.setStatus(409);
        response.setHeader("X-Identity-Mismatch", "1");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"身份已变化，请重新登录\",\"code\":\"identity_mismatch\"}");
        return false;
    }
}
