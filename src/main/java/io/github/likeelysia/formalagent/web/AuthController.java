package io.github.likeelysia.formalagent.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录状态查询:前端用它判断会话是否还有效,避免只靠"接口 401"猜。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", authentication != null && authentication.isAuthenticated());
        body.put("username", authentication == null ? "" : authentication.getName());
        return body;
    }
}
