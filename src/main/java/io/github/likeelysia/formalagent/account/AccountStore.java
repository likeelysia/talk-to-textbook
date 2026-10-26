package io.github.likeelysia.formalagent.account;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 账号持久化：轻量 SQLite。只存账号/角色/状态/会话版本，不把业务数据迁进数据库。
 *
 * <p>数据库文件默认 {@code data/accounts/accounts.db}；测试可传临时路径。
 * 不使用 WAL，规模很小；备份时停写后复制即可，避免只复制运行中的主库。
 */
@Component
public class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);

    private final Path db;
    private final Object lock = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public AccountStore(@Value("${fa.account.db:data/accounts/accounts.db}") String file) {
        this(Path.of(file == null || file.isBlank() ? "data/accounts/accounts.db" : file));
    }

    public AccountStore(Path db) {
        this.db = db;
        synchronized (lock) {
            init();
        }
    }

    public Optional<Account> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        synchronized (lock) {
            String sql = "select user_id,username,password_hash,enabled,role,created_at,session_version "
                    + "from accounts where username=?";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, username.strip());
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? Optional.of(read(r)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new AgentException("账号读取失败:" + e.getMessage(), e);
            }
        }
    }

    public Optional<Account> findById(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        synchronized (lock) {
            String sql = "select user_id,username,password_hash,enabled,role,created_at,session_version "
                    + "from accounts where user_id=?";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, userId);
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? Optional.of(read(r)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new AgentException("账号读取失败:" + e.getMessage(), e);
            }
        }
    }

    public List<Account> list() {
        synchronized (lock) {
            String sql = "select user_id,username,password_hash,enabled,role,created_at,session_version "
                    + "from accounts order by created_at, username";
            List<Account> out = new ArrayList<>();
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql);
                 ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(read(r));
                return List.copyOf(out);
            } catch (SQLException e) {
                throw new AgentException("账号列表失败:" + e.getMessage(), e);
            }
        }
    }

    public int count() {
        synchronized (lock) {
            try (Connection c = open(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select count(*) from accounts")) {
                return r.next() ? r.getInt(1) : 0;
            } catch (SQLException e) {
                throw new AgentException("账号计数失败:" + e.getMessage(), e);
            }
        }
    }

    public void insert(Account account) {
        synchronized (lock) {
            String sql = "insert into accounts(user_id,username,password_hash,enabled,role,created_at,session_version) "
                    + "values(?,?,?,?,?,?,?)";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, account.userId());
                p.setString(2, account.username());
                p.setString(3, account.passwordHash());
                p.setInt(4, account.enabled() ? 1 : 0);
                p.setString(5, account.role());
                p.setLong(6, account.createdAt());
                p.setLong(7, account.sessionVersion());
                p.executeUpdate();
            } catch (SQLException e) {
                throw new AgentException("账号创建失败(登录名可能已存在):" + e.getMessage(), e);
            }
        }
    }

    public void updatePassword(String userId, String passwordHash) {
        synchronized (lock) {
            String sql = "update accounts set password_hash=?, session_version=session_version+1 where user_id=?";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, passwordHash);
                p.setString(2, userId);
                if (p.executeUpdate() != 1) throw new AgentException("账号不存在:" + userId);
            } catch (SQLException e) {
                throw new AgentException("密码重置失败:" + e.getMessage(), e);
            }
        }
    }

    public void setEnabled(String userId, boolean enabled) {
        synchronized (lock) {
            String sql = "update accounts set enabled=?, session_version=session_version+1 where user_id=?";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setInt(1, enabled ? 1 : 0);
                p.setString(2, userId);
                if (p.executeUpdate() != 1) throw new AgentException("账号不存在:" + userId);
            } catch (SQLException e) {
                throw new AgentException("账号状态更新失败:" + e.getMessage(), e);
            }
        }
    }

    /** 登录成功后把当前版本写进 session；停用/重置会让旧 session 版本不匹配。 */
    public long bumpSessionVersion(String userId) {
        synchronized (lock) {
            String sql = "update accounts set session_version=session_version+1 where user_id=?";
            try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, userId);
                p.executeUpdate();
            } catch (SQLException e) {
                throw new AgentException("会话版本更新失败:" + e.getMessage(), e);
            }
            return findById(userId).map(Account::sessionVersion)
                    .orElseThrow(() -> new AgentException("账号不存在:" + userId));
        }
    }

    private void init() {
        try {
            Path parent = db.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.executeUpdate("create table if not exists accounts("
                        + "user_id text primary key,"
                        + "username text not null unique,"
                        + "password_hash text not null,"
                        + "enabled integer not null,"
                        + "role text not null,"
                        + "created_at integer not null,"
                        + "session_version integer not null default 0)");
            }
            log.info("账号库就绪:{}", db.toAbsolutePath());
        } catch (Exception e) {
            throw new AgentException("账号库初始化失败:" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("缺少 sqlite-jdbc", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static Account read(ResultSet r) throws SQLException {
        return new Account(
                r.getString("user_id"),
                r.getString("username"),
                r.getString("password_hash"),
                r.getInt("enabled") == 1,
                r.getString("role"),
                r.getLong("created_at"),
                r.getLong("session_version"));
    }
}
