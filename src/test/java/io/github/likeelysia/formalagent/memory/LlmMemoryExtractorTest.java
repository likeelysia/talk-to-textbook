package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.llm.LlmClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 提炼器的两件事:怎么问模型、怎么把模型的畸形返回抠出可用条目。 */
@DisplayName("LlmMemoryExtractor 提炼 + 解析容错")
class LlmMemoryExtractorTest {

    @TempDir
    Path tmp;

    /** 假模型:固定回一个字符串,顺便记下最后一次提问。 */
    static class FakeLlm implements LlmClient {
        String reply = "[]";
        RuntimeException boom;
        List<Message> last;

        @Override
        public String chat(List<Message> history, ChatOptions options) {
            if (boom != null) {
                throw boom;
            }
            last = List.copyOf(history);
            return reply;
        }
    }

    private LlmMemoryExtractor extractor(FakeLlm llm, int maxItems) {
        AppConfig.Memory.LongTerm config = new AppConfig.Memory.LongTerm(
                true, tmp.toString(), 6, 0.5, 5, 0.9, maxItems, 20, 500);
        return new LlmMemoryExtractor(llm, new ObjectMapper(), config);
    }

    private static List<ChatMessage> turns() {
        return List.of(
                ChatMessage.user("我在准备 Java 实习面试,以后回答请给出来源"),
                ChatMessage.assistant("好的,以后每次都会标出来源。", null));
    }

    /** 断言通常只关心"抠出来的原文",分数另外单测。 */
    private static List<String> texts(List<MemoryCandidate> candidates) {
        return candidates.stream().map(MemoryCandidate::text).toList();
    }

    @Test
    @DisplayName("标准 JSON:取 text,分数不够的丢掉")
    void parsesObjectsAndFiltersByScore() {
        FakeLlm llm = new FakeLlm();
        llm.reply = """
                [{"text":"用户在准备 Java 实习面试","keep":0.9},
                 {"text":"今天天气不错","keep":0.2}]""";

        assertThat(texts(extractor(llm, 5).extract(turns())))
                .containsExactly("用户在准备 Java 实习面试");
    }

    @Test
    @DisplayName("模型给的是字符串数组:照样收(它已经挑过了)")
    void acceptsPlainStringArray() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "[\"用户偏好给出处\",\"用户有一本《大学物理学》\"]";

        assertThat(texts(extractor(llm, 5).extract(turns())))
                .containsExactly("用户偏好给出处", "用户有一本《大学物理学》");
    }

    @Test
    @DisplayName("套壳 + ```json 围栏:剥掉都能认")
    void stripsFencesAndWrappers() {
        FakeLlm llm = new FakeLlm();
        llm.reply = """
                ```json
                {"memories":[{"content":"用户偏好给出处","keep":0.8}]}
                ```""";

        assertThat(texts(extractor(llm, 5).extract(turns()))).containsExactly("用户偏好给出处");
    }

    @Test
    @DisplayName("数组被塞进字符串里(见过的套壳):也要能抠出来")
    void unwrapsJsonInsideString() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"type\":\"json_object\",\"content\":\"[{\\\"text\\\":\\\"用户偏好给出处\\\",\\\"keep\\\":0.8}]\"}";

        assertThat(texts(extractor(llm, 5).extract(turns()))).containsExactly("用户偏好给出处");
    }

    @Test
    @DisplayName("压根不是 JSON:退化成一行一条")
    void fallsBackToLines() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "1. 用户在准备 Java 实习面试\n- 用户偏好给出处";

        assertThat(texts(extractor(llm, 5).extract(turns())))
                .containsExactly("用户在准备 Java 实习面试", "用户偏好给出处");
    }

    @Test
    @DisplayName("去重 + 封顶条数")
    void dedupesAndCaps() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "[\"同一个事实\",\"同一个事实\",\"第二条\",\"第三条\",\"第四条\"]";

        assertThat(texts(extractor(llm, 3).extract(turns())))
                .containsExactly("同一个事实", "第二条", "第三条");
    }

    @Test
    @DisplayName("分数原样带出来:用户明确要求记住的(keep=1.0)要能被认出来")
    void keepsScoresSoUserRequestedOnesGetPinned() {
        FakeLlm llm = new FakeLlm();
        llm.reply = """
                [{"text":"用户叫鹏哥","keep":1.0},
                 {"text":"用户喜欢看游戏资讯","keep":0.6}]""";

        List<MemoryCandidate> candidates = extractor(llm, 5).extract(turns());

        assertThat(texts(candidates)).containsExactly("用户叫鹏哥", "用户喜欢看游戏资讯");
        assertThat(candidates.get(0).mustRemember()).isTrue();
        assertThat(candidates.get(1).mustRemember()).isFalse();
    }

    @Test
    @DisplayName("没给分数的字符串:默认 0.5,照收(第二道闸只在模型真给了分时才筛)")
    void missingScoreDefaultsToKeep() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "[\"用户偏好给出处\"]";

        List<MemoryCandidate> candidates = extractor(llm, 5).extract(turns());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).keep()).isEqualTo(MemoryCandidate.KEEP_DEFAULT);
        assertThat(candidates.get(0).mustRemember()).isFalse();
    }

    @Test
    @DisplayName("模型挂了 / 返回空:返回空表,绝不抛")
    void failureIsSwallowed() {
        FakeLlm boom = new FakeLlm();
        boom.boom = new IllegalStateException("429 限流");
        assertThat(extractor(boom, 5).extract(turns())).isEmpty();

        FakeLlm blank = new FakeLlm();
        blank.reply = "   ";
        assertThat(extractor(blank, 5).extract(turns())).isEmpty();
    }

    @Test
    @DisplayName("整段都是寒暄:压根不叫模型(省一次调用)")
    void chatterNeverReachesTheModel() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "[\"不该被取到\"]";

        List<MemoryCandidate> result = extractor(llm, 5).extract(List.of(
                ChatMessage.user("你好"), ChatMessage.assistant("你好呀!", null)));

        assertThat(result).isEmpty();
        assertThat(llm.last).isNull();
    }
}
