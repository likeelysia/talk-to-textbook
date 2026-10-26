package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.account.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录状态查询 + CSRF token 获取。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accounts;

    public AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", authentication != null && authentication.isAuthenticated());
        body.put("username", authentication == null ? "" : authentication.getName());
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            accounts.findByUsername(authentication.getName()).ifPresent(a -> {
                body.put("userId", a.userId());
                body.put("role", a.role());
            });
        }
        return body;
    }

    /** 登录页/Spa 在 Cookie 不存在时可主动取一次 token。 */
    @GetMapping("/csrf")
    public Map<String, Object> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        Map<String, Object> body = new LinkedHashMap<>();
        if (token != null) {
            body.put("token", token.getToken());
            body.put("headerName", token.getHeaderName());
            body.put("parameterName", token.getParameterName());
        }
        return body;
    }
}
