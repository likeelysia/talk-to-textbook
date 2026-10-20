package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.llm.LlmClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 对账模型返回的 JSON 解析:只认合法 op / 只允许动它看得见的旧记忆。 */
class LlmMemoryReconcilerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static MemoryEntry entry(String id) {
        return new MemoryEntry(id, "s", "旧记忆 " + id, "c1", 1000L, 0L, false,
                MemoryEntry.ORIGIN_AUTO, new float[] {1, 0}, 0L, MemoryEntry.STATUS_ACTIVE, List.of());
    }

    @Test
    void parsesUpdateAndIgnoresHallucinatedTarget() {
        LlmClient llm = (history, options) -> """
                [
                  {"index":1,"op":"UPDATE","targetId":"m1","text":"用户要改成就业"},
                  {"index":2,"op":"DELETE","targetId":"m9"}
                ]
                """;
        LlmMemoryReconciler reconciler = new LlmMemoryReconciler(llm, mapper, true);

        Map<Integer, MemoryReconciler.Decision> decisions = reconciler.decide(
                List.of(new MemoryCandidate("用户要改成就业", 0.9),
                        new MemoryCandidate("用户说忘掉旧的", 0.9)),
                List.of(List.of(entry("m1")), List.of(entry("m2"))));

        assertThat(decisions).containsKey(0);
        assertThat(decisions.get(0).op()).isEqualTo(MemoryReconciler.UPDATE);
        assertThat(decisions.get(0).targetId()).isEqualTo("m1");
        assertThat(decisions).doesNotContainKey(1);        // m9 不在候选 2 的相似列表里,直接丢弃
    }

    @Test
    void malformedOutputFallsBackSilently() {
        LlmClient llm = (history, options) -> "不是 JSON";
        LlmMemoryReconciler reconciler = new LlmMemoryReconciler(llm, mapper, true);

        assertThat(reconciler.decide(List.of(new MemoryCandidate("x", 1.0)),
                List.of(List.of(entry("m1"))))).isEmpty();
    }

    @Test
    void disabledByConfig() {
        LlmClient llm = (history, options) -> "[]";
        LlmMemoryReconciler reconciler = new LlmMemoryReconciler(llm, mapper, false);

        assertThat(reconciler.decide(List.of(new MemoryCandidate("x", 1.0)),
                List.of(List.of(entry("m1"))))).isEmpty();
    }
}
