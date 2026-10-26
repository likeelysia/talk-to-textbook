package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.account.Account;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.exception.AgentException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 所有 owner 必须从这里取；绝不读取请求里的 userId/ownerId。 */
@Component
public class CurrentUser {

    private final AccountService accounts;

    public CurrentUser(AccountService accounts) {
        this.accounts = accounts;
    }

    public Account require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new AgentException("未登录");
        }
        return accounts.requireByUsername(authentication.getName());
    }

    public String id(Authentication authentication) {
        return require(authentication).userId();
    }

    public String role(Authentication authentication) {
        return require(authentication).role();
    }

    public boolean admin(Authentication authentication) {
        return require(authentication).admin();
    }
}
