package io.github.likeelysia.formalagent.account;

import java.nio.file.Path;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 现场发布烟测的临时普通账号入口，仅在服务器本地手工运行，不包含在业务包中。
 * 复用账号服务校验/哈希/停用逻辑；密码从进程环境读取，不放参数或日志。
 */
public final class AccountSmokeProbe {
    private AccountSmokeProbe() { }

    public static void main(String[] args) {
        if (args.length != 3 || !args[2].startsWith("codex_smoke_")) {
            throw new IllegalArgumentException("只允许指定库中的烟测账号");
        }
        AccountStore store = new AccountStore(Path.of(args[0]));
        AccountService service = new AccountService(store, new BCryptPasswordEncoder(12), "", "", "");
        if ("create".equals(args[1])) {
            String password = System.getenv("LAMPLIT_SMOKE_PASSWORD");
            service.createInvite(args[2], password, Account.ROLE_USER);
        } else if ("disable".equals(args[1])) {
            Account account = service.requireByUsername(args[2]);
            if (account.admin()) throw new IllegalArgumentException("拒绝操作管理员");
            service.setEnabled(account.userId(), false);
        } else {
            throw new IllegalArgumentException("只支持 create/disable");
        }
    }
}
