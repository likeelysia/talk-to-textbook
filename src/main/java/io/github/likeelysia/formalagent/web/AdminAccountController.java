package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.account.Account;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理员创建/停用/重置邀请账号；不开放注册，不返回密码哈希。 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accounts;
    private final CurrentUser currentUser;

    public AdminAccountController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> list(Authentication auth) {
        requireAdmin(auth);
        List<AccountView> views = accounts.list().stream().map(AccountView::of).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", views.size());
        body.put("accounts", views);
        return body;
    }

    @PostMapping
    public AccountView create(Authentication auth, @RequestBody(required = false) CreateRequest req) {
        requireAdmin(auth);
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        Account account = accounts.createInvite(req.username(), req.password(), req.role());
        return AccountView.of(account);
    }

    @PostMapping("/{id}/disable")
    public AccountView disable(Authentication auth, @PathVariable String id) {
        requireAdmin(auth);
        accounts.setEnabled(id, false);
        return AccountView.of(accounts.requireById(id));
    }

    @PostMapping("/{id}/enable")
    public AccountView enable(Authentication auth, @PathVariable String id) {
        requireAdmin(auth);
        accounts.setEnabled(id, true);
        return AccountView.of(accounts.requireById(id));
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(Authentication auth, @PathVariable String id,
                                             @RequestBody(required = false) ResetRequest req) {
        requireAdmin(auth);
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        accounts.resetPassword(id, req.password());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", id);
        body.put("reset", true);
        body.put("sessionsInvalidated", true);
        return body;
    }

    private void requireAdmin(Authentication auth) {
        try {
            if (!currentUser.admin(auth)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员");
            }
        } catch (AgentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
    }

    public record CreateRequest(String username, String password, String role) {
    }

    public record ResetRequest(String password) {
    }

    public record AccountView(String userId, String username, boolean enabled, String role,
                              long createdAt, long sessionVersion) {
        static AccountView of(Account account) {
            return new AccountView(account.userId(), account.username(), account.enabled(),
                    account.role(), account.createdAt(), account.sessionVersion());
        }
    }
}
