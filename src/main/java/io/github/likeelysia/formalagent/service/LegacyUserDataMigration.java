package io.github.likeelysia.formalagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * B 阶段旧单用户数据迁移：只把全局 conversations/memory（含 core/conflicts）复制给明确管理员。
 *
 * <p>只补目标缺失文件，不覆盖新目录已有内容；完成才写标记。目标与旧目录均保留，失败可重试。
 * A/B 没有旧数据，不从全局复制。
 */
@Component
public class LegacyUserDataMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyUserDataMigration.class);

    private final AccountService accounts;
    private final AppConfig config;
    private final ObjectMapper mapper;

    public LegacyUserDataMigration(AccountService accounts, AppConfig config, ObjectMapper mapper) {
        this.accounts = accounts;
        this.config = config;
        this.mapper = mapper;
    }

    @PostConstruct
    public void migrate() {
        String admin = accounts.adminUserId();
        Path userRoot = Path.of(config.conversation().dir()).resolve("users").resolve(admin);
        Path conversationTarget = userRoot.resolve("conversations");
        Path memoryTarget = userRoot.resolve("memory");
        Path marker = userRoot.resolve(".legacy-userdata-v1");

        if (Files.exists(marker)) return;

        try {
            copyMissing(Path.of(config.conversation().dir()), conversationTarget,
                    Path.of(config.conversation().dir()).resolve("users"));
            copyMissing(Path.of(config.memory().longTerm().dir()), memoryTarget,
                    Path.of(config.memory().longTerm().dir()).resolve("users"));
            Files.createDirectories(userRoot);
            Files.writeString(marker, "v1");
            log.info("旧用户数据已复制给 admin owner:{}", admin);
        } catch (IOException e) {
            throw new AgentException("旧用户数据迁移失败,未发布新路径:" + e.getMessage(), e);
        }
    }

    /** 只复制源目录第一层文件/子目录内容；跳过 users 子树，避免把目标复制回自己。 */
    private void copyMissing(Path source, Path target, Path usersSubtree) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (p.startsWith(usersSubtree)) continue;
                Path rel = source.relativize(p);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else if (p.getFileName().toString().endsWith(".json")) {
                    validateJson(p);
                    if (Files.exists(dest)) validateJson(dest);
                    else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } else if (!Files.exists(dest)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void validateJson(Path p) throws IOException {
        try {
            mapper.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new IOException("迁移源/目标 JSON 损坏:" + p + " -> " + e.getMessage(), e);
        }
    }
}
