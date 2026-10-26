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
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** B 阶段旧单用户数据迁移：按资源类型显式校验/复制，完成才写 v2 标记。 */
@Component
public class LegacyUserDataMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyUserDataMigration.class);
    private static final int SUPPORTED_MEMORY_VERSION = 1;

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
            copyConversations(conversationSource, conversationTarget,
                    conversationSource.resolve("users"));
            copyMemories(memorySource, memoryTarget, memorySource.resolve("users"));
            // 显式配置的 core/conflicts 优先；默认目录残留不覆盖有效配置来源。
            copyConfigured(Path.of(corePath), memoryTarget.resolve("core.md"), Type.CORE);
            copyConfigured(Path.of(conflictsPath), memoryTarget.resolve("conflicts.json"), Type.CONFLICTS);
            Files.createDirectories(userRoot);
            Files.writeString(marker, "v2");
            log.info("旧用户数据已复制给 admin owner:{}", admin);
        } catch (IOException e) {
            throw new AgentException("旧用户数据迁移失败,未发布新路径:" + e.getMessage(), e);
        }
    }

    private void copyConversations(Path source, Path target, Path usersSubtree) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (p.startsWith(usersSubtree) || Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                Path dest = target.resolve(source.relativize(p));
                if (name.equals("index.json")) copyFile(p, dest, Type.INDEX);
                else if (name.equals("owners.json")) {
                    Path ownersDest = target.getParent().resolve("legacy-owners.json");
                    copyFile(p, ownersDest, Type.OWNERS);
                }
                else if (name.endsWith(".json")) copyFile(p, dest, Type.CONVERSATION);
            }
        }
    }

    private void copyMemories(Path source, Path target, Path usersSubtree) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (p.startsWith(usersSubtree) || Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.equals("core.md") || name.equals("conflicts.json")) continue;
                if (name.endsWith(".json")) {
                    copyFile(p, target.resolve(source.relativize(p)), Type.MEMORY);
                }
            }
        }
    }

    private void copyConfigured(Path source, Path target, Type type) throws IOException {
        if (!Files.exists(source)) return;
        if (Files.exists(target) && Files.size(target) > 0) {
            validate(target, type);
            log.info("目标已有有效 {}，保留用户内容:{}", type, target);
            return;
        }
        copyFile(source, target, type);
    }

    private void copyFile(Path source, Path target, Type type) throws IOException {
        validate(source, type);
        if (Files.exists(target)) {
            validate(target, type);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void validate(Path p, Type type) throws IOException {
        if (type == Type.CORE) {
            String text = Files.readString(p);
            if (text == null) throw new IOException("core 不可读:" + p);
            return;
        }
        JsonNode node = mapper.readTree(Files.readString(p));
        if (node == null || !node.isObject()) {
            throw new IOException("迁移文件结构不是对象(" + type + "):" + p);
        }
        switch (type) {
            case INDEX -> {
                if (!node.has("conversations") || !node.get("conversations").isArray()) {
                    throw new IOException("会话索引结构非法:" + p);
                }
            }
            case CONVERSATION -> {
                if (!node.has("id") || !node.has("messages") || !node.get("messages").isArray()) {
                    throw new IOException("会话结构非法:" + p);
                }
            }
            case OWNERS -> {
                if (!node.isObject() || node.size() == 0) {
                    throw new IOException("归属元数据结构非法:" + p);
                }
                node.fields().forEachRemaining(e -> {
                    if (!e.getValue().isTextual()) {
                        throw new IllegalArgumentException("归属元数据值必须是字符串:" + p);
                    }
                });
            }
            case MEMORY -> {
                if (!node.has("version") || !node.has("entries")
                        || !node.get("entries").isArray()) {
                    throw new IOException("记忆结构非法:" + p);
                }
                int version = node.get("version").asInt(-1);
                if (version < 1 || version > SUPPORTED_MEMORY_VERSION) {
                    throw new IOException("不支持的记忆版本 " + version + ":" + p);
                }
            }
            case CONFLICTS -> {
                if (!node.has("conflicts") || !node.get("conflicts").isArray()) {
                    throw new IOException("冲突记录结构非法:" + p);
                }
            }
            default -> { }
        }
    }

    private enum Type { INDEX, CONVERSATION, OWNERS, MEMORY, CORE, CONFLICTS }
}
