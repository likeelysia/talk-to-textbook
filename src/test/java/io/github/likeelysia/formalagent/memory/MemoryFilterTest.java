package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.likeelysia.formalagent.conversation.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 第一道闸:能一眼判死的(寒暄 / 确认 / 太短)就别花钱叫模型。 */
@DisplayName("MemoryFilter 规则预筛")
class MemoryFilterTest {

    private static ChatMessage user(String text) {
        return ChatMessage.user(text);
    }

    private static ChatMessage assistant(String text) {
        return ChatMessage.assistant(text, null);
    }

    @Test
    @DisplayName("寒暄 / 客套:带标点、带大小写也认得出来")
    void smallTalkIsRejected() {
        assertThat(MemoryFilter.worthKeeping(user("你好!"), assistant("你好呀,有什么可以帮你的吗?"), 20)).isFalse();
        assertThat(MemoryFilter.worthKeeping(user("谢谢~"), assistant("不客气,还有问题随时问。"), 20)).isFalse();
        assertThat(MemoryFilter.worthKeeping(user("HELLO"), assistant("Hi! 今天想问点什么?"), 20)).isFalse();
    }

    @Test
    @DisplayName("纯确认 / 催更:单看这一句没有信息量")
    void acknowledgementsAreRejected() {
        assertThat(MemoryFilter.worthKeeping(user("好的。"), assistant("那我们接着看下一节的内容,这里要讲…"), 20)).isFalse();
        assertThat(MemoryFilter.worthKeeping(user("继续"), assistant("好,继续讲动量守恒的条件。"), 20)).isFalse();
    }

    @Test
    @DisplayName("太短的一轮:合起来都不够长")
    void tooShortIsRejected() {
        assertThat(MemoryFilter.worthKeeping(user("啥意思"), assistant("就那样"), 20)).isFalse();
    }

    @Test
    @DisplayName("有内容的一轮:放行(值不值得记交给模型打分)")
    void substantiveTurnPasses() {
        assertThat(MemoryFilter.worthKeeping(
                user("我在准备 Java 实习面试,以后回答请给出来源"),
                assistant("好的,以后每次都会标出来源。"), 20)).isTrue();
    }

    @Test
    @DisplayName("缺一半(没有提问 / 没有回答 / 空白)一律不算一轮")
    void incompleteTurnIsRejected() {
        assertThat(MemoryFilter.worthKeeping(user("我在准备 Java 实习面试,以后回答请给出来源"), null, 20)).isFalse();
        assertThat(MemoryFilter.worthKeeping(null, assistant("…"), 20)).isFalse();
        assertThat(MemoryFilter.worthKeeping(user("   "), assistant("有内容"), 20)).isFalse();
    }

    @Test
    @DisplayName("prescreen:整轮剔除,有料的那轮原样留下")
    void prescreenDropsWholeTurns() {
        List<ChatMessage> kept = MemoryFilter.prescreen(List.of(
                user("你好"), assistant("你好!"),
                user("我在准备物理考试,请以后回答都给出处"), assistant("好的,记下了。")), 20);

        assertThat(kept).hasSize(2);
        assertThat(kept.get(0).content()).contains("物理考试");
        assertThat(kept.get(1).content()).contains("记下了");
    }

    @Test
    @DisplayName("开头没有对应提问的助手消息:不单独留下(半轮最容易让模型记错)")
    void orphanAssistantMessageIsDropped() {
        List<ChatMessage> kept = MemoryFilter.prescreen(List.of(
                assistant("这句话前面没有提问"),
                user("请记住我的偏好:回答要简短,直接给结论"), assistant("好的。")), 20);

        assertThat(kept).extracting(ChatMessage::content).noneMatch(c -> c.contains("没有提问"));
        assertThat(kept).extracting(ChatMessage::content).anyMatch(c -> c.contains("我的偏好"));
    }
}
