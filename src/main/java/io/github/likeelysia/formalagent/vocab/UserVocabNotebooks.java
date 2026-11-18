package io.github.likeelysia.formalagent.vocab;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 一个账号对应一本生词本；同账号复用实例，使文件读写共享同一把锁。 */
@Component
public class UserVocabNotebooks {
    private final ObjectMapper mapper;
    private final AccountService accounts;
    private final VocabNotebook legacy;
    private final Path userDirectory;
    private final Map<String, VocabNotebook> notebooks = new ConcurrentHashMap<>();

    public UserVocabNotebooks(ObjectMapper mapper, AccountService accounts, VocabNotebook legacy,
                             @Value("${fa.vocab.notebook-file:data/vocab/notebook.json}") String file) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.legacy = legacy;
        this.userDirectory = Path.of(file).toAbsolutePath().normalize().getParent().resolve("users");
    }

    public VocabNotebook of(String ownerId) {
        if (ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new AgentException("非法账号标识");
        }
        // 旧版的唯一生词本继续属于原管理员，不复制给新账号。
        if (ownerId.equals(accounts.adminUserId())) return legacy;
        return notebooks.computeIfAbsent(ownerId, id -> new VocabNotebook(
                mapper, userDirectory.resolve(id).resolve("notebook.json"), Clock.systemUTC()));
    }
}
