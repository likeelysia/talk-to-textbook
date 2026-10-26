package io.github.likeelysia.formalagent.account;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Spring Security 主体，额外携带内部 userId / 角色 / 会话版本。 */
public class AccountUserDetails extends User {

    private final String userId;
    private final String role;
    private final long sessionVersion;

    public AccountUserDetails(Account account) {
        super(account.username(), account.passwordHash(), account.enabled(), true, true, true,
                authorities(account));
        this.userId = account.userId();
        this.role = account.role();
        this.sessionVersion = account.sessionVersion();
    }

    public String userId() {
        return userId;
    }

    public String role() {
        return role;
    }

    public long sessionVersion() {
        return sessionVersion;
    }

    private static Collection<? extends GrantedAuthority> authorities(Account account) {
        return java.util.List.of(new SimpleGrantedAuthority("ROLE_" + account.role()));
    }
}
