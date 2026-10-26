package io.github.likeelysia.formalagent.conversation;

import io.github.likeelysia.formalagent.account.AccountService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时把旧全局笔记本只迁给管理员；A/B 新账号保持空白。 */
@Component
public class LegacyNoteMigration {

    private final AccountService accounts;
    private final NoteStore notes;

    public LegacyNoteMigration(AccountService accounts, NoteStore notes) {
        this.accounts = accounts;
        this.notes = notes;
    }

    @PostConstruct
    public void migrate() {
        notes.migrateLegacy(accounts.adminUserId());
    }
}
