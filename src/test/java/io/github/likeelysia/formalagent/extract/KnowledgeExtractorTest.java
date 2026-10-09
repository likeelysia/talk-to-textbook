package io.github.likeelysia.formalagent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.LlmClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.likeelysia.formalagent.llm.ChatOptions;

/**
 * KnowledgeExtractor 的单元测试。
 * 用的是打桩的"假客户端" —— 所以这一套测试不联网、不烧 token、毫秒级跑完。
 */
class KnowledgeExtractorTest {

    /** 测试替身:固定返回我们准备好的字符串,顺便记住"业务层到底发了什么历史给我"。 */
    private static class FakeLlmClient implements LlmClient {
        private final String answer;
        private List<Message> lastHistory;
        private int calls;
        private ChatOptions lastOptions;

        FakeLlmClient(String answer) { this.answer = answer; }

        @Override public String chat(List<Message> history, ChatOptions options) {
            this.lastHistory = history;
            this.lastOptions = options;
            this.calls++;
            return answer;
        }
    }

    /** 测试里手动装配:一个替身客户端 + 一个普通 ObjectMapper 就够了(不用起 Spring 容器)。 */
    private static KnowledgeExtractor extractor(LlmClient client) {
        return new KnowledgeExtractor(client, new ObjectMapper());
    }

    @Test
    @DisplayName("正常 JSON → 解析出两个知识点,字段都对")
    void parsesPlainJson() {
        FakeLlmClient fake = new FakeLlmClient(
                "[{\"name\":\"IoC\",\"detail\":\"控制反转,把创建对象的权力交给容器\"},"
                        + "{\"name\":\"DI\",\"detail\":\"依赖注入,容器把依赖塞进对象\"}]");

        List<KnowledgePoint> points = extractor(fake).extract("IoC 就是控制反转……");

        assertEquals(2, points.size());
        assertEquals("IoC", points.get(0).name());
        assertEquals("控制反转,把创建对象的权力交给容器", points.get(0).detail());
    }


    @Test
    @DisplayName("空白文本 → 返回空列表,并且一次请求都不发")
    void blankChunkSendsNothing() {
        FakeLlmClient fake = new FakeLlmClient("[]");

        assertTrue(extractor(fake).extract("   \n  ").isEmpty());
        assertEquals(0, fake.calls);
    }

    @Test
    @DisplayName("模型返回空数组 [] → 空列表(不是 null)")
    void emptyArrayGivesEmptyList() {
        List<KnowledgePoint> points = extractor(new FakeLlmClient("[]")).extract("目录:第一章");

        assertTrue(points.isEmpty());
    }

    @Test
    @DisplayName("模型胡说八道(不是 JSON)→ 抛 AgentException,报错里带原始返回")
    void badResponseThrows() {
        FakeLlmClient fake = new FakeLlmClient("抱歉,我无法完成这个请求。");

        AgentException e = assertThrows(AgentException.class, () -> extractor(fake).extract("随便一段"));

        assertTrue(e.getMessage().contains("抱歉"), "报错信息里应该带上模型的原始返回,方便排查");
    }

    @Test
    @DisplayName("发给模型的是两条消息:system 提示词 + user 文本块")
    void sendsSystemAndUser() {
        FakeLlmClient fake = new FakeLlmClient("[]");

        extractor(fake).extract("第一段内容");

        assertEquals(2, fake.lastHistory.size());
        assertEquals("system", fake.lastHistory.get(0).getRole());
        assertEquals("user", fake.lastHistory.get(1).getRole());
        assertEquals("第一段内容", fake.lastHistory.get(1).getContent());
    }

    @Test
    @DisplayName("提取时必须要求 JSON mode(结构化输出)")
    void requestsJsonMode() {
        FakeLlmClient fake = new FakeLlmClient("[]");

        extractor(fake).extract("随便一段");

        assertTrue(fake.lastOptions.jsonMode(), "提取调用应开启 jsonMode");
    }

    // ---------- 以下为容错解析(真机灌《大学物理学》撞出来的坑) ----------

    @Test
    @DisplayName("容错:模型把多个对象并排吐出来(没有数组外壳)→ 照样捞回")
    void salvagesConcatenatedObjects() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"name\":\"轨道角动量\",\"detail\":\"沿袭经典力学名词所称的轨道角动量。\"} "
                        + "{\"name\":\"角动量大小公式\",\"detail\":\"由角量子数 l 决定。\"}");

        List<KnowledgePoint> points = extractor(fake).extract("角动量那一段……");

        assertEquals(2, points.size());
        assertEquals("轨道角动量", points.get(0).name());
        assertEquals("角动量大小公式", points.get(1).name());
    }

    @Test
    @DisplayName("容错:外面包了壳({\"points\":[...]})→ 截最外层数组")
    void salvagesWrappedArray() {
        FakeLlmClient fake = new FakeLlmClient("{\"points\":[{\"name\":\"位矢\",\"detail\":\"从原点指向质点的有向线段\"}]}");

        List<KnowledgePoint> points = extractor(fake).extract("位矢……");

        assertEquals(1, points.size());
        assertEquals("位矢", points.get(0).name());
    }

    @Test
    @DisplayName("容错:代码围栏 + 引导语包裹 → 仍然能解析")
    void salvagesFencedWithProse() {
        FakeLlmClient fake = new FakeLlmClient(
                "好的,以下是提取结果:\n```json\n[{\"name\":\"位移\",\"detail\":\"位置的变化量\"}]\n```\n以上。");

        List<KnowledgePoint> points = extractor(fake).extract("位移……");

        assertEquals(1, points.size());
        assertEquals("位移", points.get(0).name());
    }

    @Test
    @DisplayName("容错:detail 里带花括号也不切错")
    void handlesBracesInsideStrings() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"name\":\"集合表示法\",\"detail\":\"形如 {a, b, c} 的写法\"} "
                        + "{\"name\":\"空集\",\"detail\":\"不含任何元素的集合\"}");

        List<KnowledgePoint> points = extractor(fake).extract("集合……");

        assertEquals(2, points.size());
        assertEquals("形如 {a, b, c} 的写法", points.get(0).detail());
        assertEquals("空集", points.get(1).name());
    }

    @Test
    @DisplayName("容错:只有一个对象 → 当成单条知识点")
    void salvagesSingleObject() {
        FakeLlmClient fake = new FakeLlmClient("{\"name\":\"匀加速直线运动\",\"detail\":\"加速度恒定的直线运动\"}");

        List<KnowledgePoint> points = extractor(fake).extract("匀加速……");

        assertEquals(1, points.size());
    }

    @Test
    @DisplayName("容错:数组被套进字符串里({\"type\":\"json_object\",\"content\":\"[...]\"})→ 剥壳再解")
    void unwrapsStringPayload() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"type\":\"json_object\",\"content\":\"[{\\\"name\\\":\\\"热力学概率\\\",\\\"detail\\\":\\\"宏观状态对应的概率。\\\"}]\"}");

        List<KnowledgePoint> points = extractor(fake).extract("热力学……");

        assertEquals(1, points.size());
        assertEquals("热力学概率", points.get(0).name());
    }

    @Test
    @DisplayName("容错:name 空白的条目当噪声丢掉")
    void dropsBlankNames() {
        FakeLlmClient fake = new FakeLlmClient(
                "[{\"name\":\"\",\"detail\":\"没有名字\"},{\"name\":\"速度\",\"detail\":\"位置变化率\"}]");

        List<KnowledgePoint> points = extractor(fake).extract("速度……");

        assertEquals(1, points.size());
        assertEquals("速度", points.get(0).name());
    }
}
