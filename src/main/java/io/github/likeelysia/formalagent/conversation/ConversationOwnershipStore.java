package io.github.likeelysia.formalagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话归属的最小边界：先只用于“聊天存笔记”的授权。
 *
 * <p>安全语义：
 * <ul>
 *   <li>文件不存在 → 旧单用户库兼容，管理员可访问未登记会话；</li>
 *   <li>文件存在但无该会话记录 → 拒绝，不能再把未知 ID 永远归给 admin；</li>
 *   <li>文件解析/IO 失败 → 全部拒绝并保留原件，不能用空映射覆盖；</li>
 *   <li>claim 先写盘成功再更新内存，失败不留下“内存有、磁盘没有”的矛盾状态。</li>
 * </ul>
 */
@Component
public class ConversationOwnershipStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationOwnershipStore.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final Object lock = new Object();
    private final Map<String, String> owners = new LinkedHashMap<>();

    /** 只有文件从未存在时才允许旧单用户兼容；文件存在（哪怕空）就不再默认 admin。 */
    private boolean legacyUninitialized;
    /** 非 null 表示归属文件不可信，所有授权 fail-closed。 */
    private String loadError;

    public ConversationOwnershipStore(ObjectMapper mapper,
                                      @Value("${fa.conversation.owner-file:data/conversations/owners.json}") String file) {
        this.mapper = mapper;
        this.file = Path.of(file == null || file.isBlank()
                ? "data/conversations/owners.json" : file);
        synchronized (lock) {
            read();
        }
    }

    /** owner 为 null 只用于旧单测；生产调用必须传认证主体里的 userId。 */
    public boolean canAccess(String conversationId, String ownerId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        if (ownerId == null || ownerId.isBlank()) return true;
        synchronized (lock) {
            if (loadError != null) return false;
            String owner = owners.get(conversationId);
            if (owner != null) return owner.equals(ownerId);
            return legacyUninitialized && "admin".equals(ownerId);
        }
    }

    public void claim(String conversationId, String ownerId) {
        if (conversationId == null || conversationId.isBlank()
                || ownerId == null || ownerId.isBlank()) {
            throw new AgentException("会话归属参数不合法");
        }
        synchronized (lock) {
            if (loadError != null) {
                throw new AgentException("会话归属文件不可信,拒绝写入:" + loadError);
            }
            if (owners.containsKey(conversationId)) return;
            Map<String, String> next = new LinkedHashMap<>(owners);
            next.put(conversationId, ownerId);
            write(next);
            owners.put(conversationId, ownerId);
            legacyUninitialized = false;
        }
    }

    private void read() {
        if (!Files.exists(file)) {
            legacyUninitialized = true;
            return;
        }
        try {
            Map<String, String> parsed = mapper.readValue(Files.readString(file),
                    new TypeReference<Map<String, String>>() { });
            if (parsed == null) {
                throw new IOException("归属文件内容为 null");
            }
            owners.putAll(parsed);
            legacyUninitialized = false;
        } catch (IOException e) {
            // 不清空、不覆盖；保留原件，让人工恢复。
            loadError = e.getMessage();
            log.error("会话归属文件损坏({}),授权全部拒绝,原文件保留:{}", file, e.getMessage());
        }
    }

    private void write(Map<String, String> snapshot) {
        Path tmp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new AgentException("会话归属保存失败:" + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }
}
