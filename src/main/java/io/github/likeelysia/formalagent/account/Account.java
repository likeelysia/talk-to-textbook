package io.github.likeelysia.formalagent.account;

/** 持久化账号；userId 是内部稳定标识，登录名只是外部凭据。 */
public record Account(
        String userId,
        String username,
        String passwordHash,
        boolean enabled,
        String role,
        long createdAt,
        long sessionVersion) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public boolean admin() {
        return ROLE_ADMIN.equals(role);
    }
}
