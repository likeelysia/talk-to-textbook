package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用大模型做记忆对账(默认实现)。
 *
 * <p>一次提炼只多调一次模型:把所有候选 + 每条候选的相似旧记忆打包成一个 Prompt,
 * 让模型返回"第几条、什么操作、动哪条"。返回不合法/调用失败时返回空表,
 * 由 {@link LongTermMemory} 退回"太像就合并、否则新增"的老逻辑 —— 宁可少改,不要写坏。
 */
@Component
public class LlmMemoryReconciler implements MemoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryReconciler.class);

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final CoreMemoryStore coreMemory;

    @Autowired
    public LlmMemoryReconciler(LlmClient llm, ObjectMapper mapper,
                               @Value("${fa.memory.long-term.reconcile:true}") boolean enabled,
                               CoreMemoryStore coreMemory) {
        this.llm = llm;
        this.mapper = mapper;
        this.enabled = enabled;
        this.coreMemory = coreMemory;
    }

    /** 测试 / 手工装配用:不接核心档案。 */
    public LlmMemoryReconciler(LlmClient llm, ObjectMapper mapper, boolean enabled) {
        this(llm, mapper, enabled, null);
    }

    @Override
    public Map<Integer, Decision> decide(List<MemoryCandidate> candidates,
                                         List<List<MemoryEntry>> neighbors) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        String raw;
        try {
            raw = llm.chat(List.of(
                    new Message("system", Prompts.get("memory-reconcile")),
                    new Message("user", payload(candidates, neighbors,
                            coreMemory == null ? "" : coreMemory.read()))));
        } catch (RuntimeException e) {
            log.warn("记忆对账调用模型失败,本次退回旧合并逻辑:{}", e.getMessage());
            return Map.of();
        }
        Map<Integer, Decision> decisions = parse(raw, candidates, neighbors);
        if (!decisions.isEmpty()) {
            log.info("记忆对账:{} 条候选得到 {} 个决策", candidates.size(), decisions.size());
        }
        return decisions;
    }

    /** 把候选、相似记忆和核心档案排成模型好读的文本。 */
    private static String payload(List<MemoryCandidate> candidates, List<List<MemoryEntry>> neighbors,
                                  String coreText) {
        StringBuilder sb = new StringBuilder();
        if (coreText != null && !coreText.isBlank()) {
            sb.append("【核心档案(用户手工维护,不要修改它;只用来判断冲突)】\n")
                    .append(coreText.strip()).append("\n\n");
        }
        sb.append("【候选记忆】\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i).text()).append('\n');
            List<MemoryEntry> near = i < neighbors.size() ? neighbors.get(i) : List.of();
            if (near.isEmpty()) {
                sb.append("   相似记忆:（无）\n");
            } else {
                sb.append("   相似记忆:\n");
                for (MemoryEntry entry : near) {
                    sb.append("   - id=").append(entry.id())
                            .append(" | pinned=").append(entry.pinned())
                            .append(" | ").append(entry.text()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private Map<Integer, Decision> parse(String raw, List<MemoryCandidate> candidates,
                                         List<List<MemoryEntry>> neighbors) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String json = stripFence(raw.strip());
        try {
            JsonNode root = mapper.readTree(json);
            if (root.isObject()) {
                for (String key : List.of("decisions", "items", "results", "data")) {
                    JsonNode wrapper = root.get(key);
                    if (wrapper != null && wrapper.isArray()) {
                        root = wrapper;
                        break;
                    }
                }
            }
            if (!root.isArray()) {
                log.warn("记忆对账返回不是数组,忽略:{}", abbreviate(raw));
                return Map.of();
            }

            Map<Integer, Decision> out = new LinkedHashMap<>();
            for (JsonNode node : root) {
                int index = node.path("index").asInt(-1);
                int candidateIndex = index - 1;           // Prompt 里从 1 开始
                if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
                    continue;
                }
                String op = node.path("op").asText(node.path("operation").asText(""))
                        .trim().toUpperCase(Locale.ROOT);
                String targetId = node.path("targetId").asText(
                        node.path("target_id").asText("")).trim();
                String text = node.path("text").asText("").trim();

                if (CORE_CONFLICT.equals(op)) {
                    out.put(candidateIndex, new Decision(op, null, null));
                    continue;
                }
                if (ADD.equals(op) || NOOP.equals(op)) {
                    out.put(candidateIndex, new Decision(op, null, null));
                    continue;
                }
                if (!UPDATE.equals(op) && !DELETE.equals(op)) {
                    continue;
                }
                if (targetId.isEmpty() || !isNeighbor(targetId, neighbors, candidateIndex)) {
                    continue;                          // 只允许动它看得见的旧记忆,防模型编 id
                }
                if (UPDATE.equals(op)) {
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (text.length() > MemoryEntry.TEXT_MAX) {
                        text = text.substring(0, MemoryEntry.TEXT_MAX);
                    }
                }
                out.put(candidateIndex, new Decision(op, targetId, UPDATE.equals(op) ? text : null));
            }
            return out;
        } catch (Exception e) {
            log.warn("记忆对账返回解析失败({}),忽略:{}", e.getMessage(), abbreviate(raw));
            return Map.of();
        }
    }

    private static boolean isNeighbor(String targetId, List<List<MemoryEntry>> neighbors, int candidateIndex) {
        if (candidateIndex >= neighbors.size()) {
            return false;
        }
        for (MemoryEntry entry : neighbors.get(candidateIndex)) {
            if (entry.id().equals(targetId)) {
                return true;
            }
        }
        return false;
    }

    private static String stripFence(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static String abbreviate(String raw) {
        String one = raw.replaceAll("\\s+", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }
}
