package io.github.likeelysia.formalagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import io.github.likeelysia.formalagent.memory.LlmSessionSummarizer;
import io.github.likeelysia.formalagent.memory.ShortTermMemory;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一轮问答的编排:上下文怎么进去、摘要什么时候压、哪一步不许烧 token。
 *
 * <p>这里用"真 AgentService + 假模型 + 临时目录里的真会话"跑,尽量少打桩 ——
 * 编排放错地方(比如历史插错位置)只有这种测法才抓得住。
 */
@DisplayName("AssistantService 一轮问答编排")
class AssistantServiceTest {

    @TempDir
    Path tmp;

    /** 假模型:工具通道按剧本回,普通通道(摘要用)固定回一句。 */
    static class FakeLlm implements LlmClient {
        final Deque<AssistantTurn> script = new ArrayDeque<>();
        final List<List<AgentMessage>> rounds = new ArrayList<>();
        String summaryReply = "模型摘要:用户在问物理。";
        RuntimeException summaryBoom;

        @Override
        public String chat(List<Message> history, ChatOptions options) {
            if (summaryBoom != null) {
                throw summaryBoom;
            }
            return summaryReply;
        }

        @Override
        public AssistantTurn chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            rounds.add(new ArrayList<>(messages));
            return script.isEmpty() ? new AssistantTurn("(默认回答)", null) : script.poll();
        }
    }

    private FakeLlm llm;
    private ConversationService conversations;

    @BeforeEach
    void setUp() {
        llm = new FakeLlm();
        conversations = new ConversationService(new ConversationStore(new ObjectMapper(), tmp));
        conversations.init();
    }

    private AssistantService assistant(int maxTurns, boolean summarize) {
        ShortTermMemory memory = new ShortTermMemory(
                new AppConfig.Memory.ShortTerm(maxTurns, 6000, summarize), new LlmSessionSummarizer(llm));
        return new AssistantService(new AgentService(llm, List.of(), new ObjectMapper()), conversations, memory);
    }

    private static List<String> sent(List<AgentMessage> messages) {
        return messages.stream().map(AgentMessage::content).toList();
    }

    @Test
    @DisplayName("多轮:第二问能看到第一问和它的回答,且提问排在最后")
    void secondTurnSeesTheFirst() {
        AssistantService assistant = assistant(6, true);
        String id = conversations.create("多轮").id();
        llm.script.add(new AssistantTurn("位移是零。", null));
        llm.script.add(new AssistantTurn("速度是矢量。", null));

        AssistantService.Turn first = assistant.ask(id, "位移是多少?", "位移是多少?");
        assistant.ask(id, "那速度呢?", "那速度呢?");

        assertThat(first.conversationId()).isEqualTo(id);
        assertThat(llm.rounds).hasSize(2);

        List<String> second = sent(llm.rounds.get(1));
        assertThat(second.get(0)).contains("教材讲解助手");          // 系统提示词仍在最前
        assertThat(second.get(1)).isEqualTo("位移是多少?");           // 历史紧随其后
        assertThat(second.get(2)).isEqualTo("位移是零。");
        assertThat(second.get(second.size() - 1)).isEqualTo("那速度呢?");   // 当前提问在最后
    }

    @Test
    @DisplayName("超出窗口 -> 旧的一轮压进摘要,并作为 system 消息跟着走")
    void overflowingTurnsAreSummarized() {
        AssistantService assistant = assistant(1, true);
        String id = conversations.create("会聊很久").id();

        assistant.ask(id, "问题一", "问题一");
        assistant.ask(id, "问题二", "问题二");
        assistant.ask(id, "问题三", "问题三");

        Conversation conv = conversations.get(id);
        assertThat(conv.summary()).contains("模型摘要");
        assertThat(conv.summarizedCount()).isEqualTo(2);              // 水位推过第一轮

        List<String> third = sent(llm.rounds.get(2));
        assertThat(third.get(0)).contains("教材讲解助手");
        assertThat(third.get(1)).contains("模型摘要");                 // 摘要以 system 身份排在历史前
        assertThat(third).contains("问题二").contains("问题三").doesNotContain("问题一");
    }

    @Test
    @DisplayName("摘要模型挂了也不影响问答:退化成「之前问过什么」的兜底摘要")
    void summaryFailureDoesNotBreakTheTurn() {
        AssistantService assistant = assistant(1, true);
        String id = conversations.create("摘要会挂").id();
        llm.summaryBoom = new IllegalStateException("模型超时");

        assistant.ask(id, "问题一", "问题一");
        assistant.ask(id, "问题二", "问题二");
        assistant.ask(id, "问题三", "问题三");

        Conversation conv = conversations.get(id);
        assertThat(conv.summary()).contains("之前问过:").contains("问题一");
        assertThat(conv.summarizedCount()).isEqualTo(2);              // 水位照样推进,历史不白丢
        assertThat(conv.messages()).hasSize(6);
    }

    @Test
    @DisplayName("关掉摘要:超窗口的旧对话直接丢,只留最近几轮")
    void windowOnlyModeDropsOldTurns() {
        AssistantService assistant = assistant(1, false);
        String id = conversations.create("不摘要").id();

        assistant.ask(id, "问题一", "问题一");
        assistant.ask(id, "问题二", "问题二");
        assistant.ask(id, "问题三", "问题三");

        Conversation conv = conversations.get(id);
        assertThat(conv.summary()).isNull();
        assertThat(conv.summarizedCount()).isZero();
        assertThat(sent(llm.rounds.get(2))).doesNotContain("问题一").contains("问题二", "问题三");
        assertThat(conv.messages()).hasSize(6);                       // 原文照样留在盘上
    }

    @Test
    @DisplayName("会话不存在 -> 报错,一个字都不发给模型")
    void unknownConversationBurnsNothing() {
        AssistantService assistant = assistant(6, true);

        assertThatThrownBy(() -> assistant.ask("ghost", "在吗", "在吗"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");

        assertThat(llm.rounds).isEmpty();
    }

    @Test
    @DisplayName("会话 id 不传 -> 落到默认对话,响应里带回它的 id")
    void blankIdFallsBackToDefault() {
        AssistantService assistant = assistant(6, true);

        AssistantService.Turn turn = assistant.ask(null, "你好", "你好");

        assertThat(turn.conversationId()).isEqualTo(Conversation.DEFAULT_ID);
        assertThat(conversations.get(Conversation.DEFAULT_ID).messages()).hasSize(2);
    }

    @Test
    @DisplayName("这一轮会被记进会话:用户原话 + 助手回答 + 工具轨迹")
    void recordsTheTurn() {
        AssistantService assistant = assistant(6, true);
        String id = conversations.create("记账").id();
        llm.script.add(new AssistantTurn("查到了。", null));

        assistant.ask(id, "拼给模型的长提问", "用户原话");

        Conversation conv = conversations.get(id);
        assertThat(conv.messages()).hasSize(2);
        assertThat(conv.messages().get(0).content()).isEqualTo("用户原话");
        assertThat(conv.messages().get(1).content()).isEqualTo("查到了。");
    }
}
