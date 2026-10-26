package io.github.likeelysia.formalagent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理接口鉴权:请求头 {@code X-Admin-Key} 必须等于配置里的密钥。
 *
 * <p><b>为什么需要</b>:管理接口(入库)能写知识库、能读服务器文件;而且<b>每个问答请求都会
 * 花我们的 API key 的钱</b> —— 不设鉴权等于把钱包挂在公网上。
 *
 * <p>密钥从环境变量来({@code FA_ADMIN_KEY}),不落配置文件。**没配时自动关闭**并打 WARN ——
 * 本地开发方便,上线前务必设上。
 */
@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyInterceptor.class);

    private final String adminKey;

    public AdminKeyInterceptor(@Value("${fa.security.admin-key:}") String adminKey) {
        this.adminKey = adminKey == null ? "" : adminKey.strip();
        if (this.adminKey.isBlank()) {
            log.warn("未配置 fa.security.admin-key:管理接口将拒绝请求;"
                    + "上线前请设置环境变量 FA_ADMIN_KEY");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (adminKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"服务器未配置管理密钥，管理接口已拒绝\"}");
            return false;
        }
        if (adminKey.equals(request.getHeader("X-Admin-Key"))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"需要管理员密钥(请求头 X-Admin-Key)\"}");
        return false;
    }
}
