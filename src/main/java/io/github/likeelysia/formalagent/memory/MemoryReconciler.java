package io.github.likeelysia.formalagent.memory;

import java.util.List;
import java.util.Map;

/**
 * 记忆对账器:拿"新提炼出来的候选"跟"已有的相似记忆"比一比,决定加 / 改 / 删 / 不动。
 *
 * <p>返回的 Map 以候选下标(0 开始)为 key;没有决策的候选会走 {@link LongTermMemory} 的兜底逻辑
 * (太像就合并、否则新增)。这样即使对账模型挂了,也不会把记忆写坏。
 */
public interface MemoryReconciler {

    String ADD = "ADD";
    String UPDATE = "UPDATE";
    String DELETE = "DELETE";
    String NOOP = "NOOP";

    /**
     * @param op        ADD / UPDATE / DELETE / NOOP
     * @param targetId  UPDATE / DELETE 时要动的旧记忆 id
     * @param text      UPDATE 时的新文本
     */
    record Decision(String op, String targetId, String text) {
    }

    Map<Integer, Decision> decide(List<MemoryCandidate> candidates, List<List<MemoryEntry>> neighbors);
}
