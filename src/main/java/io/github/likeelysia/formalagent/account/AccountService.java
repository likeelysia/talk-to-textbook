package io.github.likeelysia.formalagent.account;

import io.github.likeelysia.formalagent.exception.AgentException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 持久化邀请账号：管理员创建，不开放注册。
 *
 * <p>启动时如果账号库为空，用现有的 {@code FA_AUTH_USERNAME}/{@code FA_AUTH_PASSWORD(_HASH)}
 * 建一个 ADMIN；旧个人版升级时登录仍用原口令。账号只在 SQLite 中保存 BCrypt 哈希。
 */
@Service
public class AccountService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");

    private final AccountStore store;
    private final PasswordEncoder encoder;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final String bootstrapHash;

    private volatile String adminUserId;

    public AccountService(AccountStore store, PasswordEncoder encoder,
                          @Value("${fa.auth.username:pengge}") String bootstrapUsername,
                          @Value("${fa.auth.password:}") String bootstrapPassword,
                          @Value("${fa.auth.password-hash:}") String bootstrapHash) {
        this.store = store;
        this.encoder = encoder;
        this.bootstrapUsername = bootstrapUsername == null || bootstrapUsername.isBlank()
                ? "pengge" : bootstrapUsername.strip();
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        this.bootstrapHash = bootstrapHash == null ? "" : bootstrapHash.strip();
    }

    @PostConstruct
    public void bootstrap() {
        if (store.count() > 0) {
            store.findByUsername(bootstrapUsername).filter(Account::admin)
                    .ifPresent(a -> adminUserId = a.userId());
            if (adminUserId == null) {
                store.list().stream().filter(Account::admin).findFirst()
                        .ifPresent(a -> adminUserId = a.userId());
            }
            return;
        }
        String hash = bootstrapHash.isBlank() && !bootstrapPassword.isBlank()
                ? encoder.encode(bootstrapPassword) : bootstrapHash;
        if (hash.isBlank()) {
            throw new IllegalStateException(
                    "账号库为空且未配置初始管理员:请设置 FA_AUTH_PASSWORD_HASH 或 FA_AUTH_PASSWORD");
        }
        Account admin = new Account("admin", bootstrapUsername, hash, true,
                Account.ROLE_ADMIN, System.currentTimeMillis(), 0);
        store.insert(admin);
        adminUserId = admin.userId();
        log.info("初始化管理员账号:{}", admin.username());
    }

    public String adminUserId() {
        if (adminUserId == null) {
            store.list().stream().filter(Account::admin).findFirst()
                    .ifPresent(a -> adminUserId = a.userId());
        }
        return adminUserId == null ? "admin" : adminUserId;
    }

    public Optional<Account> findByUsername(String username) {
        return store.findByUsername(username);
    }

    public Account requireByUsername(String username) {
        return store.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    public Account requireById(String userId) {
        return store.findById(userId)
                .orElseThrow(() -> new AgentException("账号不存在:" + userId));
    }

    public List<Account> list() {
        return store.list();
    }

    public Account createInvite(String username, String password, String role) {
        String name = username == null ? "" : username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new AgentException("登录名必须是 3–32 位字母、数字、下划线、点或短横");
        }
        if (password == null || password.length() < 8) {
            throw new AgentException("密码至少 8 位");
        }
        String normalizedRole = role == null || role.isBlank()
                ? Account.ROLE_USER : role.strip().toUpperCase(Locale.ROOT);
        if (!Account.ROLE_ADMIN.equals(normalizedRole) && !Account.ROLE_USER.equals(normalizedRole)) {
            throw new AgentException("角色只能是 ADMIN 或 USER");
        }
        if (store.findByUsername(name).isPresent()) {
            throw new AgentException("登录名已存在");
        }
        String userId = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Account account = new Account(userId, name, encoder.encode(password), true,
                normalizedRole, System.currentTimeMillis(), 0);
        store.insert(account);
        log.info("创建邀请账号:{} role={} userId={}", name, normalizedRole, userId);
        return account;
    }

    public void setEnabled(String userId, boolean enabled) {
        Account account = requireById(userId);
        if (account.admin() && !enabled && store.list().stream()
                .filter(Account::admin).filter(Account::enabled).count() <= 1) {
            throw new AgentException("不能停用最后一个启用的管理员");
        }
        store.setEnabled(userId, enabled);
    }

    public void resetPassword(String userId, String password) {
        if (password == null || password.length() < 8) {
            throw new AgentException("密码至少 8 位");
        }
        requireById(userId);
        store.updatePassword(userId, encoder.encode(password));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = store.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
        if (!account.enabled()) throw new UsernameNotFoundException("账号已停用");
        return new AccountUserDetails(account);
    }
}
