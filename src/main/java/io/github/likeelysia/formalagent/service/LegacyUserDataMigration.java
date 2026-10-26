package io.github.likeelysia.formalagent.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B 阶段旧单用户数据迁移：只把全局 conversations/memory 复制给明确管理员。
 * 目标与旧目录均保留；按文件类型校验结构，完成才写 v2 标记；旧 v1 标记会重新校验。
 */
@Component
public class LegacyUserDataMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyUserDataMigration.class);

    private final AccountService accounts;
    private final AppConfig config;
    private final ObjectMapper mapper;
    private final String corePath;
    private final String conflictsPath;

    public LegacyUserDataMigration(AccountService accounts, AppConfig config, ObjectMapper mapper,
                                   @Value("${fa.memory.core.path:data/memory/core.md}") String corePath,
                                   @Value("${fa.memory.conflicts-path:data/memory/conflicts.json}") String conflictsPath) {
        this.accounts = accounts;
        this.config = config;
        this.mapper = mapper;
        this.corePath = corePath;
        this.conflictsPath = conflictsPath;
    }

    @PostConstruct
    public void migrate() {
        String admin = accounts.adminUserId();
        Path userRoot = Path.of(config.conversation().dir()).resolve("users").resolve(admin);
        Path conversationTarget = userRoot.resolve("conversations");
        Path memoryTarget = userRoot.resolve("memory");
        Path marker = userRoot.resolve(".legacy-userdata-v1");

        if (Files.exists(marker)) {
            try {
                String m = Files.readString(marker).strip();
                if ("v2".equals(m)) return;
                log.warn("发现旧版迁移标记({}),重新校验并补齐", m);
            } catch (IOException e) {
                throw new AgentException("迁移标记不可读:" + e.getMessage(), e);
            }
        }

        try {
            Path conversationSource = Path.of(config.conversation().dir());
            Path memorySource = Path.of(config.memory().longTerm().dir());
            copyMissing(conversationSource, conversationTarget,
                    conversationSource.resolve("users"));
            copyMissing(memorySource, memoryTarget,
                    memorySource.resolve("users"));
            copyConfigured(Path.of(corePath), memoryTarget.resolve("core.md"), false);
            copyConfigured(Path.of(conflictsPath), memoryTarget.resolve("conflicts.json"), true);
            Files.createDirectories(userRoot);
            Files.writeString(marker, "v2");
            log.info("旧用户数据已复制给 admin owner:{}", admin);
        } catch (IOException e) {
            throw new AgentException("旧用户数据迁移失败,未发布新路径:" + e.getMessage(), e);
        }
    }

    private void copyMissing(Path source, Path target, Path usersSubtree) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (p.startsWith(usersSubtree)) continue;
                Path rel = source.relativize(p);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    copyFile(p, dest, p.getFileName().toString().endsWith(".json"));
                }
            }
        }
    }

    private void copyConfigured(Path source, Path target, boolean json) throws IOException {
        if (Files.exists(source)) copyFile(source, target, json);
    }

    private void copyFile(Path source, Path target, boolean json) throws IOException {
        if (json) validate(source);
        if (Files.exists(target)) {
            if (json) validate(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** 按会话/索引/记忆/冲突的真实结构校验，语法正确但结构错误也必须拒绝。 */
    private void validate(Path p) throws IOException {
        JsonNode node = mapper.readTree(Files.readString(p));
        if (node == null || !node.isObject()) {
            throw new IOException("迁移文件结构不是对象:" + p);
        }
        String name = p.getFileName().toString();
        String path = p.toString().replace('\\', '/');
        if (name.equals("index.json")) {
            if (!node.has("conversations") || !node.get("conversations").isArray()) {
                throw new IOException("会话索引结构非法:" + p);
            }
        } else if (path.contains("/memory/") && name.equals("conflicts.json")) {
            if (!node.has("conflicts") || !node.get("conflicts").isArray()) {
                throw new IOException("冲突记录结构非法:" + p);
            }
        } else if (path.contains("/memory/")) {
            if (!node.has("entries") || !node.get("entries").isArray() || !node.has("version")) {
                throw new IOException("记忆结构非法:" + p);
            }
        } else if (!node.has("id") || !node.has("messages") || !node.get("messages").isArray()) {
            throw new IOException("会话结构非法:" + p);
        }
    }
}
