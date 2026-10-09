package io.github.likeelysia.formalagent.extract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识点提取器:把"一块文本"交给大模型,拿回这一块里的知识点列表。
 * 注意:只做一次性调用,不走 ChatService(不碰会话历史)。
 *
 * <p><b>为什么要容错解析</b>:我们让模型用 JSON 模式吐 {@code [{name, detail}, ...]},绝大多数时候没问题;
 * 但真机灌《大学物理学》时确实撞上过模型把多个对象<b>并排</b>吐出来(没有数组外壳)→
 * 老代码一句"不是预期的 JSON 数组"就把整块丢了,一丢就是 2~4 个知识点。
 * 现在按四级降级解析,尽量把模型给的东西捞回来(捞不到才认输抛异常)。
 */
@Component
public class KnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractor.class);

    private static final TypeReference<List<KnowledgePoint>> POINT_LIST = new TypeReference<>() {};

    private final LlmClient client;
    private final ObjectMapper mapper;               // ← 容器注入的统一实例(不再是各自 new)

    public KnowledgeExtractor(LlmClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** 一块文本 → 这块里的知识点列表。空文本直接返回空列表(不浪费一次请求)。 */
    public List<KnowledgePoint> extract(String chunk) {
        if (chunk == null || chunk.isBlank()) return List.of();

        String raw = client.chat(
                List.of(new Message("system", Prompts.get("knowledge-extract")), new Message("user", chunk)),
                ChatOptions.JSON);          // ← 关键:要求模型以 JSON 输出
        return parse(raw);
    }

    /**
     * 把模型返回的文本"洗"成知识点列表。五级降级:
     * <ol>
     *   <li>整段就是数组(含空数组 {@code []})→ 直接用</li>
     *   <li>外面包了壳或有引导语 → 截最外层 {@code [...]} 再试(兼容 {@code {"points":[...]}})</li>
     *   <li>多个对象并排、没有数组外壳 → 按"顶层花括号"逐个捞回来合并</li>
     *   <li>数组被塞进字符串再包一层({@code {"type":"json_object","content":"[...]"}})→ 剥壳再解</li>
     *   <li>都不行 → 抛 {@link AgentException}(把原始返回带上,方便排查)</li>
     * </ol>
     */
    private List<KnowledgePoint> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = stripFences(raw);

        List<KnowledgePoint> points = tryParseArray(text);      // ①
        if (points != null) return points;

        String sliced = sliceArray(text);                       // ②
        if (sliced != null && !sliced.equals(text)) {
            points = tryParseArray(sliced);
            if (points != null) return points;
        }

        points = scanTopLevelObjects(text);                     // ③
        if (!points.isEmpty()) {
            log.warn("模型没按 JSON 数组返回,已按「并排对象」容错捞回 {} 条知识点", points.size());
            return points;
        }

        points = unwrapStringPayload(text);                     // ④
        if (points != null) {
            log.warn("模型把数组套在字符串里返回,已剥壳捞回 {} 条知识点", points.size());
            return points;
        }

        throw new AgentException("模型返回的不是预期的 JSON 数组:" + abbreviate(raw));   // ⑤
    }

    /**
     * 剥壳:有的模型会返回 {@code {"type":"json_object","content":"[ {...} ]"}} ——
     * 数组被塞进字符串字段里。这里把对象里的文本字段再当成 JSON 解一遍。
     *
     * @return 捞到的知识点;无从下手时返回 {@code null}(交给调用方继续降级)
     */
    private List<KnowledgePoint> unwrapStringPayload(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        for (JsonNode value : root) {
            if (!value.isTextual()) continue;
            String inner = value.asText();
            List<KnowledgePoint> parsed = tryParseArray(inner);
            if (parsed == null) parsed = keepUsable(scanTopLevelObjects(inner));
            if (!parsed.isEmpty()) return parsed;
        }
        return null;
    }

    /** 能整段读成数组就返回(空数组返回空列表);读不动返回 {@code null}。 */
    private List<KnowledgePoint> tryParseArray(String s) {
        try {
            List<KnowledgePoint> points = mapper.readValue(s, POINT_LIST);
            return points == null ? List.of() : List.copyOf(keepUsable(points));
        } catch (Exception e) {
            return null;
        }
    }

    /** 只留"有名有姓"的条目 —— name 空白的当噪声丢掉(容错路径尤其需要)。 */
    private static List<KnowledgePoint> keepUsable(List<KnowledgePoint> points) {
        List<KnowledgePoint> ok = new ArrayList<>(points.size());
        for (KnowledgePoint p : points) {
            if (p != null && p.name() != null && !p.name().isBlank()) ok.add(p);
        }
        return ok;
    }

    /** 去掉 Markdown 代码围栏等噪音。 */
    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.strip();
        }
        return s;
    }

    /** 截取最外层的 {@code [...]} 片段;找不到返回 {@code null}。 */
    private static String sliceArray(String s) {
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : null;
    }

    /**
     * 扫描所有"顶层"的 {@code {...}} 片段,逐个反序列化后合并。
     * 手写的括号配对要认字符串与转义 —— 否则 detail 里出现 {@code {a, b}} 这种花括号会切错。
     */
    private List<KnowledgePoint> scanTopLevelObjects(String s) {
        List<KnowledgePoint> points = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    addIfUsable(points, s.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return points;
    }

    /** 单个 {@code {...}} 片段 → 知识点;读不出来/没名字就静默跳过(容错路径不因一段坏片段全丢)。 */
    private void addIfUsable(List<KnowledgePoint> sink, String json) {
        try {
            KnowledgePoint p = mapper.readValue(json, KnowledgePoint.class);
            if (p != null && p.name() != null && !p.name().isBlank()) sink.add(p);
        } catch (Exception e) {
            log.debug("容错解析:跳过一段读不出来的片段 {}", abbreviate(json));
        }
    }

    private String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }
}
