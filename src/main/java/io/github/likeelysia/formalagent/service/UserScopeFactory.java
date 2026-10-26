package io.github.likeelysia.formalagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.memory.CoreMemoryStore;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.MemoryConflictStore;
import io.github.likeelysia.formalagent.memory.MemoryExtractor;
import io.github.likeelysia.formalagent.memory.MemoryReconciler;
import io.github.likeelysia.formalagent.memory.MemoryStore;
import io.github.likeelysia.formalagent.memory.MemoryTaskExecutor;
import io.github.likeelysia.formalagent.memory.ShortTermMemory;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** One immutable data scope per authenticated owner. */
@Service
public class UserScopeFactory {

    private static final String OWNER_PATTERN = "[A-Za-z0-9_-]{1,40}";

    private final ObjectMapper mapper;
    private final AppConfig config;
    private final AgentService agent;
    private final ShortTermMemory shortTerm;
    private final MemoryExtractor extractor;
    private final EmbeddingClient embedder;
    private final MemoryReconciler reconciler;
    private final MemoryTaskExecutor executor;
    private final Map<String, UserData> cache = new ConcurrentHashMap<>();

    public UserScopeFactory(ObjectMapper mapper, AppConfig config, AgentService agent,
                            ShortTermMemory shortTerm, MemoryExtractor extractor,
                            EmbeddingClient embedder, MemoryReconciler reconciler,
                            MemoryTaskExecutor executor) {
        this.mapper = mapper;
        this.config = config;
        this.agent = agent;
        this.shortTerm = shortTerm;
        this.extractor = extractor;
        this.embedder = embedder;
        this.reconciler = reconciler;
        this.executor = executor;
    }

    public UserData of(String ownerId) {
        if (ownerId == null || !ownerId.matches(OWNER_PATTERN)) {
            throw new AgentException("非法 ownerId");
        }
        return cache.computeIfAbsent(ownerId, this::create);
    }

    private UserData create(String ownerId) {
        Path userRoot = Path.of(config.conversation().dir()).resolve("users").resolve(ownerId);
        Path conversationDir = userRoot.resolve("conversations");
        Path memoryDir = userRoot.resolve("memory");
        ConversationService conversations = new ConversationService(
                new ConversationStore(mapper, conversationDir));
        conversations.ensureDefault();
        MemoryStore store = new MemoryStore(mapper, memoryDir, config.embedding().model());
        MemoryConflictStore conflicts = new MemoryConflictStore(
                mapper, memoryDir.resolve("conflicts.json"));
        LongTermMemory memory = new LongTermMemory(
                store, extractor, embedder, conversations, config.memory().longTerm(),
                executor, reconciler, conflicts);
        CoreMemoryStore core = new CoreMemoryStore(memoryDir.resolve("core.md").toString(), 800);
        AssistantService assistant = new AssistantService(agent, conversations, shortTerm, memory, core);
        return new UserData(conversations, memory, core, conflicts, assistant);
    }

    public record UserData(ConversationService conversations, LongTermMemory memory,
                           CoreMemoryStore coreMemory, MemoryConflictStore conflicts,
                           AssistantService assistant) {
    }
}
