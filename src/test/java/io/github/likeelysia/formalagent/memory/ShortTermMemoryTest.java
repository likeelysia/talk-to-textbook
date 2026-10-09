package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 短期记忆的窗口算术:最近几轮带原文、更早的压摘要、摘要只吃一次。
 *
 * <p>这块是纯计算,不碰网络/磁盘,所以这里全用"造数据 + 断言"的方式压边界。
 */
@DisplayName("ShortTermMemory 窗口裁剪")
class ShortTermMemoryTest {

    /** 假摘要器:固定回一句,并记下被压的是哪几条。 */
    static class FakeSummarizer implements SessionSummarizer {
        String reply = "摘要:用户在问物理。";
        RuntimeException boom;
        List<ChatMessage> lastEvicted;
        String lastPrevious;

        @Override
        public String summarize(String previousSummary, List<ChatMessage> evicted) {
            this.lastPrevious = previousSummary;
            this.lastEvicted = evicted;
            if (boom != null) {
                throw boom;
            }
            return reply;
        }
    }

    /** 造一个聊了 n 轮的会话(每轮 = 一条提问 + 一条回答)。 */
    private static Conversation conversation(int turns) {
        Conversation c = Conversation.of("c1", "测试会话");
        for (int i = 1; i <= turns; i++) {
            c = c.appended(ChatMessage.user("问题" + i)).appended(ChatMessage.assistant("回答" + i, null));
        }
        return c;
    }

    private static ShortTermMemory memory(int maxTurns, int maxChars, boolean summarize, SessionSummarizer s) {
        return new ShortTermMemory(new AppConfig.Memory.ShortTerm(maxTurns, maxChars, summarize), s);
    }

    private static List<String> contents(ShortTermMemory.Context ctx) {
        return ctx.messages().stream()
                .filter(m -> !"system".equals(m.role()))       // system 是摘要,单独断言
                .map(AgentMessage::content)
                .toList();
    }

    @Test
    @DisplayName("空会话:没有任何历史,也不用摘要")
    void emptyConversation() {
        ShortTermMemory memory = memory(6, 6000, true, new FakeSummarizer());

        ShortTermMemory.Context ctx = memory.build(conversation(0));

        assertThat(ctx.messages()).isEmpty();
        assertThat(ctx.evicted()).isEmpty();
        assertThat(ctx.coversUpTo()).isZero();
    }

    @Test
    @DisplayName("没超窗口:全部带上,一条都不压")
    void everythingFitsInTheWindow() {
        ShortTermMemory memory = memory(6, 6000, true, new FakeSummarizer());

        ShortTermMemory.Context ctx = memory.build(conversation(3));

        assertThat(ctx.messages()).hasSize(6);
        assertThat(ctx.evicted()).isEmpty();
    }

    @Test
    @DisplayName("超窗口:只带最近 N 轮,而且是以「轮」为界(不会留下半截上下文)")
    void keepsWholeRecentTurns() {
        ShortTermMemory memory = memory(2, 6000, true, new FakeSummarizer());

        ShortTermMemory.Context ctx = memory.build(conversation(5));

        assertThat(contents(ctx)).containsExactly("问题4", "回答4", "问题5", "回答5");
        assertThat(ctx.messages().get(0).role()).isEqualTo("user");
        assertThat(ctx.evicted()).hasSize(6);
        assertThat(ctx.coversUpTo()).isEqualTo(6);
    }

    @Test
    @DisplayName("字符预算先到:同一轮里塞长文也不会把上下文撑爆")
    void charBudgetCutsBeforeTurnLimit() {
        ShortTermMemory memory = memory(6, 30, true, new FakeSummarizer());
        Conversation c = Conversation.of("c1", "长文")
                .appended(ChatMessage.user("短问题一"))
                .appended(ChatMessage.assistant("答".repeat(40), null))
                .appended(ChatMessage.user("短问题二"))
                .appended(ChatMessage.assistant("短回答二", null));

        ShortTermMemory.Context ctx = memory.build(c);

        assertThat(contents(ctx)).containsExactly("短问题二", "短回答二");
        assertThat(ctx.evicted()).hasSize(2);
    }

    @Test
    @DisplayName("最近一轮再长也留着 —— 否则这一轮对话就彻底断了")
    void alwaysKeepsTheLatestTurn() {
        ShortTermMemory memory = memory(6, 5, true, new FakeSummarizer());

        ShortTermMemory.Context ctx = memory.build(conversation(1));

        assertThat(contents(ctx)).containsExactly("问题1", "回答1");
    }

    @Test
    @DisplayName("有摘要:摘要作为 system 消息排在最前面,原文跟在后面")
    void summaryComesFirst() {
        ShortTermMemory memory = memory(1, 6000, true, new FakeSummarizer());
        Conversation c = conversation(3).withSummary("前面聊过位移的定义", 4);

        ShortTermMemory.Context ctx = memory.build(c);

        assertThat(ctx.messages().get(0).role()).isEqualTo("system");
        assertThat(ctx.messages().get(0).content()).contains("前面聊过位移的定义");
        assertThat(contents(ctx)).containsExactly("问题3", "回答3");
    }

    @Test
    @DisplayName("进过摘要的消息不再重复喂:水位之前的一律不带")
    void summarizedMessagesAreNotReplayed() {
        ShortTermMemory memory = memory(6, 6000, true, new FakeSummarizer());
        Conversation c = conversation(3).withSummary("已压缩", 4);

        ShortTermMemory.Context ctx = memory.build(c);

        assertThat(contents(ctx)).doesNotContain("问题1", "回答1", "问题2", "回答2");
        assertThat(contents(ctx)).containsExactly("问题3", "回答3");
        assertThat(ctx.evicted()).isEmpty();
    }

    @Test
    @DisplayName("空气泡(失败的回合)不回放,免得白占预算")
    void skipsBlankBubbles() {
        ShortTermMemory memory = memory(6, 6000, true, new FakeSummarizer());
        Conversation c = Conversation.of("c1", "t")
                .appended(ChatMessage.user("正常问题"))
                .appended(ChatMessage.assistant("   ", null));

        ShortTermMemory.Context ctx = memory.build(c);

        assertThat(contents(ctx)).containsExactly("正常问题");
    }

    @Test
    @DisplayName("摘要走模型:拿到的就是模型那句话")
    void summarizeUsesTheModel() {
        FakeSummarizer fake = new FakeSummarizer();
        fake.reply = "模型摘要:讲到位移。";
        ShortTermMemory memory = memory(1, 6000, true, fake);
        Conversation c = conversation(2);

        String summary = memory.summarize(null, c.messages());

        assertThat(summary).isEqualTo("模型摘要:讲到位移。");
        assertThat(fake.lastEvicted).hasSize(4);
    }

    @Test
    @DisplayName("摘要模型挂了 -> 本地兜底把「用户问过什么」列出来,历史不白丢")
    void summarizeFallsBackToLocalQuotes() {
        FakeSummarizer fake = new FakeSummarizer();
        fake.boom = new IllegalStateException("模型超时");
        ShortTermMemory memory = memory(1, 6000, true, fake);
        Conversation c = conversation(2);

        String summary = memory.summarize("上一版摘要", c.messages());

        assertThat(summary).contains("上一版摘要");
        assertThat(summary).contains("之前问过:");
        assertThat(summary).contains("问题1").contains("问题2");
    }

    @Test
    @DisplayName("关掉摘要:超窗口的旧对话直接丢,不调模型")
    void summarizingCanBeTurnedOff() {
        FakeSummarizer fake = new FakeSummarizer();
        ShortTermMemory memory = memory(1, 6000, false, fake);

        assertThat(memory.summarizing()).isFalse();
        assertThat(memory.build(conversation(3)).evicted()).hasSize(4);
    }
}
