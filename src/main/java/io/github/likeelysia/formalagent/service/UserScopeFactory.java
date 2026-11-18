package io.github.likeelysia.formalagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.agent.tool.ReadVocabNotebookTool;
import io.github.likeelysia.formalagent.vocab.UserVocabNotebooks;
import io.github.likeelysia.formalagent.account.UserCommitLock;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One immutable data scope per authenticated owner. */
@Service
public class UserScopeFactory {

    private static final String OWNER_PATTERN = "[A-Za-z0-9_-]{1,40}";

    private final ObjectMapper mapper;
    private final AppConfig config;
    private final AgentService agent;
    private final UserVocabNotebooks vocabNotebooks;
    private final ShortTermMemory shortTerm;
    private final MemoryExtractor extractor;
    private final EmbeddingClient embedder;
    private final MemoryReconciler reconciler;
    private final MemoryTaskExecutor executor;
    @Value("${fa.memory.core.max-chars:800}") private int coreMaxChars = 800;
    @Value("${fa.context.max-chars:14000}") private int contextMaxChars = 14000;
    @Value("${fa.context.reserve-chars:3000}") private int contextReserveChars = 3000;
    @Value("${fa.memory.long-term.recall-min-score:0.30}") private double recallMinScore = 0.30;
    private final Map<String, UserData> cache = new ConcurrentHashMap<>();

    public UserScopeFactory(ObjectMapper mapper, AppConfig config, AgentService agent,
                            ShortTermMemory shortTerm, MemoryExtractor extractor,
                            EmbeddingClient embedder, MemoryReconciler reconciler,
                            MemoryTaskExecutor executor, UserVocabNotebooks vocabNotebooks) {
        this.mapper = mapper;
        this.config = config;
        this.agent = agent;
        this.vocabNotebooks = vocabNotebooks;
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
        UserCommitLock commitLock = new UserCommitLock();
        ConversationService conversations = new ConversationService(
                new ConversationStore(mapper, conversationDir), commitLock);
        conversations.ensureDefault();
        MemoryStore store = new MemoryStore(mapper, memoryDir, config.embedding().model());
        MemoryConflictStore conflicts = new MemoryConflictStore(
                mapper, memoryDir.resolve("conflicts.json"));
        LongTermMemory memory = new LongTermMemory(
                store, extractor, embedder, conversations, config.memory().longTerm(),
                executor, reconciler, conflicts);
        memory.setCommitLock(commitLock);
        memory.setRecallMinScore(recallMinScore);
        CoreMemoryStore core = new CoreMemoryStore(memoryDir.resolve("core.md").toString(), coreMaxChars);
        // ownerId 来自 Controller 的登录身份，只在构造工具时绑定，模型参数里没有账号。
        AgentService scopedAgent = agent.withAdditionalTool(new ReadVocabNotebookTool(vocabNotebooks.of(ownerId), mapper));
        AssistantService assistant = new AssistantService(
                scopedAgent, conversations, shortTerm, memory, core, contextMaxChars, contextReserveChars);
        return new UserData(conversations, memory, core, conflicts, assistant);
    }

    public record UserData(ConversationService conversations, LongTermMemory memory,
                           CoreMemoryStore coreMemory, MemoryConflictStore conflicts,
                           AssistantService assistant) {
    }
}
