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
 * <p>旧个人版的历史会话在缺失记录时按管理员归属处理；新 USER 没有记录就不能读取/保存。
 * 完整的会话迁移、摘要、记忆 scope 和后台任务隔离属于后续阶段。
 */
@Component
public class ConversationOwnershipStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationOwnershipStore.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final Object lock = new Object();
    private final Map<String, String> owners = new LinkedHashMap<>();

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
            String owner = owners.get(conversationId);
            if (owner != null) return owner.equals(ownerId);
            // 旧个人版：没有归属记录的既有会话仍属管理员；不能因此把新 USER 放进来。
            return "admin".equals(ownerId);
        }
    }

    public void claim(String conversationId, String ownerId) {
        if (conversationId == null || conversationId.isBlank()
                || ownerId == null || ownerId.isBlank()) {
            throw new AgentException("会话归属参数不合法");
        }
        synchronized (lock) {
            String old = owners.putIfAbsent(conversationId, ownerId);
            if (old == null) write();
        }
    }

    private void read() {
        if (!Files.exists(file)) return;
        try {
            Map<String, String> parsed = mapper.readValue(Files.readString(file),
                    new TypeReference<Map<String, String>>() { });
            if (parsed != null) owners.putAll(parsed);
        } catch (IOException e) {
            log.warn("会话归属文件读不出来({}),按空归属启动:{}", file, e.getMessage());
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(),
                    file.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), owners);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AgentException("会话归属保存失败:" + e.getMessage());
        }
    }
}
