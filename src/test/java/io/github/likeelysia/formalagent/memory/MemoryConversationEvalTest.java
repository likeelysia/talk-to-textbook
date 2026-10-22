package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import io.github.likeelysia.formalagent.service.AssistantService;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 小型对话验收集:不只验证"库里多了一条",而是验证<b>下一轮真正注入给模型的上下文</b>。
 *
 * <p>覆盖:明确记住、无关问题不召回、目标变化、明确忘掉、跨会话共享、助手建议未被采纳。
 */
@DisplayName("记忆对话级验收")
class MemoryConversationEvalTest {

    @TempDir
    Path tmp;

    static class FakeLlm implements LlmClient {
        final Deque<AssistantTurn> script = new ArrayDeque<>();
        final List<List<AgentMessage>> rounds = new ArrayList<>();

        @Override
        public String chat(List<Message> history, ChatOptions options) {
            return "摘要";
        }

        @Override
        public AssistantTurn chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            rounds.add(new ArrayList<>(messages));
            return script.isEmpty() ? new AssistantTurn("默认回答", null) : script.poll();
        }
    }

    private FakeLlm llm;
    private ConversationService conversations;
    private MemoryStore store;
    private final Deque<MemoryExtractor.Result> extractions = new ArrayDeque<>();
    private final EmbeddingClient embedder = text -> new float[] {1, 0};

    @BeforeEach
    void setUp() {
        llm = new FakeLlm();
        conversations = new ConversationService(new ConversationStore(new ObjectMapper(), tmp.resolve("convs")));
        conversations.init();
        store = new MemoryStore(new ObjectMapper(), tmp.resolve("mem"), "test");
    }

    private LongTermMemory longTerm(MemoryReconciler reconciler) {
        MemoryExtractor extractor = turns -> extractions.isEmpty()
                ? MemoryExtractor.Result.empty()
                : extractions.poll();
        AppConfig.Memory.LongTerm config = new AppConfig.Memory.LongTerm(
                true, tmp.resolve("mem").toString(), 1, 0.5, 5, 0.9, 5, 0, 500);
        return new LongTermMemory(store, extractor, embedder, conversations, config,
                Runnable::run, reconciler, null);
    }

    private AssistantService assistant(LongTermMemory longTerm) {
        ShortTermMemory shortTerm = new ShortTermMemory(
                new AppConfig.Memory.ShortTerm(6, 6000, false), null);
        return new AssistantService(new AgentService(llm, List.of(), new ObjectMapper()),
                conversations, shortTerm, longTerm);
    }

    private void answer(String text) {
        llm.script.add(new AssistantTurn(text, null));
    }

    /** 某一轮实际发给模型的、包含长期记忆的那条 system 消息(没有就返回空串)。 */
    private String injectedMemory(int round) {
        return llm.rounds.get(round).stream()
                .map(AgentMessage::content)
                .filter(Objects::nonNull)
                .filter(s -> s.startsWith("[长期记忆"))
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("明确记住:下一轮确实把这条事实注入上下文")
    void explicitRememberIsInjectedNextTurn() {
        LongTermMemory memory = longTerm((candidates, neighbors) -> Map.of());
        AssistantService assistant = assistant(memory);
        String id = conversations.create("记住").id();
        extractions.add(MemoryExtractor.Result.ok(List.of(MemoryCandidate.remember("用户叫鹏哥"))));

        answer("记住了");
        assistant.ask(id, "我叫鹏哥,请记住", "我叫鹏哥,请记住");
        answer("你叫鹏哥");
        assistant.ask(id, "我叫什么?", "我叫什么?");

        assertThat(injectedMemory(1)).contains("用户叫鹏哥");
    }

    @Test
    @DisplayName("无关问题:不注入任何长期记忆")
    void unrelatedQuestionInjectsNothing() {
        LongTermMemory memory = longTerm((candidates, neighbors) -> Map.of());
        AssistantService assistant = assistant(memory);
        String id = conversations.create("无关").id();

        answer("答一");
        assistant.ask(id, "物理位移是什么?", "物理位移是什么?");
        answer("答二");
        assistant.ask(id, "今天天气怎么样?", "今天天气怎么样?");

        assertThat(injectedMemory(0)).isEmpty();
        assertThat(injectedMemory(1)).isEmpty();
    }

    @Test
    @DisplayName("目标变化:上一轮记的是考研,下一轮注入的是就业")
    void goalChangeUpdatesInjectedContext() {
        MemoryReconciler update = (candidates, neighbors) -> {
            if (!candidates.get(0).text().contains("就业") || neighbors.get(0).isEmpty()) {
                return Map.of();
            }
            return Map.of(0, new MemoryReconciler.Decision(MemoryReconciler.UPDATE,
                    neighbors.get(0).get(0).id(), candidates.get(0).text()));
        };
        LongTermMemory memory = longTerm(update);
        AssistantService assistant = assistant(memory);
        String id = conversations.create("目标变化").id();

        extractions.add(MemoryExtractor.Result.ok(List.of(MemoryCandidate.of("用户准备考研"))));
        answer("加油");
        assistant.ask(id, "我准备考研", "我准备考研");

        extractions.add(MemoryExtractor.Result.ok(List.of(MemoryCandidate.of("用户不考研了,准备就业"))));
        answer("好的");
        assistant.ask(id, "我不考研了,准备就业", "我不考研了,准备就业");

        answer("你在准备就业");
        assistant.ask(id, "我现在准备什么?", "我现在准备什么?");

        assertThat(injectedMemory(2)).contains("准备就业").doesNotContain("- 用户准备考研");
    }

    @Test
    @DisplayName("明确忘掉:下一轮不再注入被删的事实")
    void explicitForgetStopsInjection() {
        MemoryReconciler noop = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.NOOP, null, null));
        LongTermMemory memory = longTerm(noop);
        AssistantService assistant = assistant(memory);
        String id = conversations.create("忘掉").id();

        extractions.add(MemoryExtractor.Result.ok(List.of(MemoryCandidate.remember("用户养了一只猫"))));
        answer("记下了");
        assistant.ask(id, "我养了一只猫,请记住", "我养了一只猫,请记住");

        extractions.add(MemoryExtractor.Result.ok(List.of(MemoryCandidate.forget("用户说别再提猫了"))));
        answer("好的");
        assistant.ask(id, "别再提猫了", "别再提猫了");

        answer("不提了");
        assistant.ask(id, "猫呢?", "猫呢?");

        assertThat(injectedMemory(2)).doesNotContain("猫");
        assertThat(memory.list(id)).isEmpty();
    }

    @Test
    @DisplayName("跨会话共享:甲记住的,乙的下一轮也能注入")
    void sharedMemoryIsInjectedFromTheOtherConversation() {
        LongTermMemory memory = longTerm((candidates, neighbors) -> Map.of());
        AssistantService assistant = assistant(memory);
        String a = conversations.create("甲").id();
        String b = conversations.create("乙").id();
        memory.remember(a, "用户叫鹏哥");
        conversations.shareMemory(b, a);

        answer("你叫鹏哥");
        assistant.ask(b, "我叫什么?", "我叫什么?");

        assertThat(injectedMemory(0)).contains("用户叫鹏哥");
    }

    @Test
    @DisplayName("助手建议未被采纳:不会被记成'用户已决定'")
    void assistantSuggestionIsNotStoredAsUserDecision() {
        LongTermMemory memory = longTerm((candidates, neighbors) -> Map.of());
        AssistantService assistant = assistant(memory);
        String id = conversations.create("建议").id();

        extractions.add(MemoryExtractor.Result.empty());
        answer("建议你每天刷两道题");
        assistant.ask(id, "给我一个学习建议", "给我一个学习建议");

        answer("好的");
        assistant.ask(id, "我叫什么?", "我叫什么?");

        assertThat(memory.list(id)).isEmpty();
        assertThat(injectedMemory(1)).isEmpty();
    }
}
