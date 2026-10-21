package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 用大模型提炼长期记忆(默认实现)。
 *
 * <p><b>两道闸</b>:
 * <pre>
 *   ①规则预筛({@link MemoryFilter})   →  明显的寒暄/确认直接扔,连模型都不调
 *   ②模型打分(keep >= 阈值)           →  留下"值得长期记住"的
 * </pre>
 *
 * <p>提示词外置在 {@code resources/prompts/memory-extract.txt}(什么叫"值得记"是要反复调的文案)。
 *
 * <p><b>解析容错</b>:模型不一定乖乖给标准 JSON —— 见过裸数组、带 ```json 围栏、
 * {@code {"memories":[...]}} 套壳、数组塞进字符串里。这里逐层剥,实在认不出来才退化成"一行一条"。
 */
@Component
public class LlmMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);

    private static final int DEFAULT_MAX_ITEMS = 5;
    private static final double DEFAULT_KEEP_SCORE = 0.5;

    /** JSON 解析最多往里剥几层(防畸形返回把自己绕死)。 */
    private static final int MAX_DEPTH = 3;

    /** 装记忆原文的字段名(都按小写比,模型偶尔换个说法)。 */
    private static final List<String> TEXT_FIELDS =
            List.of("text", "content", "fact", "memory", "item", "point");

    /** 装分数的字段名。 */
    private static final List<String> SCORE_FIELDS = List.of("keep", "score", "importance");

    /** 用户明确要求记住的标记字段。 */
    private static final List<String> REMEMBER_FIELDS =
            List.of("remember", "explicitremember", "explicit_remember", "mustremember", "must_remember");

    /** 用户明确要求忘掉的标记字段。 */
    private static final List<String> FORGET_FIELDS =
            List.of("forget", "explicitforget", "explicit_forget", "mustforget", "must_forget");

    /** 装真正数组的外壳字段名。 */
    private static final List<String> WRAPPER_FIELDS =
            List.of("memories", "items", "points", "data", "content", "result");

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final double keepScore;
    private final int maxItems;
    private final int minChars;

    @Autowired
    public LlmMemoryExtractor(LlmClient llm, ObjectMapper mapper, AppConfig config) {
        this(llm, mapper, longTermOf(config));
    }

    /** 测试 / 手工装配用:直接给这一段配置。 */
    public LlmMemoryExtractor(LlmClient llm, ObjectMapper mapper, AppConfig.Memory.LongTerm config) {
        this.llm = llm;
        this.mapper = mapper;
        this.keepScore = config == null ? DEFAULT_KEEP_SCORE : config.keepScore();
        this.maxItems = (config == null || config.maxItems() <= 0) ? DEFAULT_MAX_ITEMS : config.maxItems();
        this.minChars = (config == null || config.minChars() <= 0)
                ? MemoryFilter.DEFAULT_MIN_CHARS
                : config.minChars();
    }

    @Override
    public Result extract(List<ChatMessage> turns) {
        List<ChatMessage> kept = MemoryFilter.prescreen(turns == null ? List.of() : turns, minChars);
        if (kept.isEmpty()) {
            return Result.empty();                  // 全是寒暄:省一次模型调用,算"成功但没有"
        }

        String raw;
        try {
            raw = llm.chat(List.of(
                    new Message("system", Prompts.get("memory-extract")),
                    new Message("user", payload(kept))));
        } catch (RuntimeException e) {
            log.warn("长期记忆提炼调用模型失败:{}", e.getMessage());
            return Result.failed();
        }
        if (raw == null || raw.isBlank()) {
            log.warn("长期记忆提炼模型返回空内容");
            return Result.failed();
        }

        List<MemoryCandidate> candidates = parse(raw);
        boolean parsed = tryRead(stripFences(raw.strip())) != null;
        if (candidates.isEmpty() && !parsed) {
            // 既不是 JSON、又一条都剥不出来 → 当成临时失败,保留水位下次重试
            log.warn("长期记忆提炼返回无法解析:{}", raw.replaceAll("\\s+", " ").strip());
            return Result.failed();
        }
        if (log.isDebugEnabled()) {
            log.debug("长期记忆候选取到 {} 条:{}", candidates.size(), candidates);
        }
        return Result.ok(candidates);
    }

    /** 给模型的输入:这一段还没提炼过的对话。 */
    private static String payload(List<ChatMessage> turns) {
        StringBuilder sb = new StringBuilder("【待整理的对话】\n");
        for (ChatMessage m : turns) {
            sb.append(m.fromUser() ? "用户:" : "助手:")
                    .append(m.content() == null ? "" : m.content())
                    .append('\n');
        }
        return sb.toString();
    }

    // ==================== 解析(逐层剥壳)====================

    /** 把模型返回解析成一串候选记忆。包可见,方便单测直接怼各种畸形返回。 */
    List<MemoryCandidate> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = stripFences(raw.strip());
        List<MemoryCandidate> out = new ArrayList<>();
        JsonNode root = tryRead(text);
        if (root != null) {
            collect(root, out, 0);
        }
        // 不是 JSON、或者读出来是个标量(比如 "1. xxx" 被读成了数字 1)—— 都当"一行一条"再试一次
        if (out.isEmpty() && (root == null || root.isValueNode())) {
            out.addAll(lines(text));
        }
        return dedupe(out);
    }

    private void collect(JsonNode node, List<MemoryCandidate> out, int depth) {
        if (node == null || node.isNull() || depth > MAX_DEPTH) {
            return;
        }
        if (node.isArray()) {
            node.forEach(el -> collect(el, out, depth + 1));
            return;
        }
        if (node.isObject()) {
            String text = firstText(node, TEXT_FIELDS);
            if (text != null) {
                String trimmed = text.strip();
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    JsonNode nested = tryRead(trimmed);       // 见过 {"content":"[...]"} 这种把数组塞进字符串的
                    if (nested != null) {
                        collect(nested, out, depth + 1);
                        return;
                    }
                }
                addEntry(node, out);                      // 它本身就是一条记忆
                return;
            }
            JsonNode wrapper = findWrapper(node);          // 或者是个外壳({"memories":[...]})
            if (wrapper != null) {
                collect(wrapper, out, depth + 1);
            }
            return;                                        // 认不出来的对象:丢掉,别把 JSON 碎片当记忆
        }
        if (node.isTextual()) {
            String s = node.asText();
            String trimmed = s.strip();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                JsonNode nested = tryRead(trimmed);
                if (nested != null) {
                    collect(nested, out, depth + 1);         // 数组/对象被塞进字符串里
                    return;
                }
            }
            out.add(MemoryCandidate.of(s));
        }
    }

    /** 一条记忆:取原文 + 取分数;分数不够就丢。 */
    private void addEntry(JsonNode node, List<MemoryCandidate> out) {
        String text = firstText(node, TEXT_FIELDS);
        if (text == null || text.isBlank()) {
            return;
        }
        Double score = firstNumber(node, SCORE_FIELDS);
        boolean remember = firstBoolean(node, REMEMBER_FIELDS);
        boolean forget = firstBoolean(node, FORGET_FIELDS);
        if (!forget && score != null && score < keepScore) {
            return;                                          // 第二道闸:可记可不记的不要
        }
        if (score == null) {
            score = MemoryCandidate.KEEP_DEFAULT;
        }
        if (score >= MemoryCandidate.KEEP_MUST) {
            remember = true;                                 // 兼容旧提示词:1.0 = 明确要求记住
        }
        // 分数是"重不重要",remember/forget 才是"用户授没授权";两者分开存
        out.add(new MemoryCandidate(text, score, remember, forget));
    }

    /** 行模式兜底:去掉列表符号,一行算一条。 */
    private static List<MemoryCandidate> lines(String text) {
        List<MemoryCandidate> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String clean = line.strip().replaceAll("^[-*•\\d.、)]+\\s*", "").strip();
            clean = clean.replaceAll("^\"|\"$", "").strip();
            if (!clean.isBlank() && !clean.equals("[]") && !clean.equals("{}")) {
                out.add(MemoryCandidate.of(clean));
            }
        }
        return out;
    }

    /** 去重 + 压平空白 + 封顶条数(按原文去重,分数取先到的那条)。 */
    private List<MemoryCandidate> dedupe(List<MemoryCandidate> raw) {
        Map<String, MemoryCandidate> unique = new LinkedHashMap<>();
        for (MemoryCandidate candidate : raw) {
            String clean = candidate.text() == null ? "" : candidate.text().replaceAll("\\s+", " ").strip();
            if (!clean.isBlank()) {
                unique.putIfAbsent(clean, new MemoryCandidate(clean, candidate.keep(),
                        candidate.explicitRemember(), candidate.explicitForget()));
            }
            if (unique.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private JsonNode tryRead(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 拆掉 {@code ```json ... ```} 围栏。 */
    private static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) {
                s = s.substring(firstLineEnd + 1);
            }
            int end = s.lastIndexOf("```");
            if (end >= 0) {
                s = s.substring(0, end);
            }
        }
        return s.strip();
    }

    private static String firstText(JsonNode node, List<String> fields) {
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            if (fields.contains(entry.getKey().toLowerCase(Locale.ROOT)) && entry.getValue().isValueNode()) {
                String value = entry.getValue().asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Double firstNumber(JsonNode node, List<String> fields) {
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            if (fields.contains(entry.getKey().toLowerCase(Locale.ROOT)) && entry.getValue().isNumber()) {
                return entry.getValue().asDouble();
            }
        }
        return null;
    }

    private static boolean firstBoolean(JsonNode node, List<String> fields) {
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            if (!fields.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isNumber()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String text = value.asText("").strip().toLowerCase(Locale.ROOT);
                return "true".equals(text) || "1".equals(text) || "yes".equals(text);
            }
        }
        return false;
    }

    private static JsonNode findWrapper(JsonNode node) {
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            if (WRAPPER_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))
                    && (entry.getValue().isContainerNode() || entry.getValue().isTextual())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static AppConfig.Memory.LongTerm longTermOf(AppConfig config) {
        return config == null || config.memory() == null ? null : config.memory().longTerm();
    }
}
