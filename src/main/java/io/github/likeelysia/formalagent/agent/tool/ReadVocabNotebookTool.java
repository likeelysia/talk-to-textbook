package io.github.likeelysia.formalagent.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.Tool;
import io.github.likeelysia.formalagent.vocab.VocabNotebook;
import java.time.Instant;
import java.util.*;

/** 每个用户单独创建，构造时绑定其生词本；模型不能指定账号，也不能修改词条。 */
public class ReadVocabNotebookTool implements Tool {
    private final VocabNotebook notebook;
    private final ObjectMapper mapper;

    public ReadVocabNotebookTool(VocabNotebook notebook, ObjectMapper mapper) {
        this.notebook = Objects.requireNonNull(notebook);
        this.mapper = mapper;
    }

    @Override public String name() { return "read_vocab_notebook"; }

    @Override public String description() {
        return "只读查询当前登录用户在背词工具中保存的生词本。用户要求查看自己的生词、抽查、"
                + "按生词出题或写短文、复习今日/某天计划、查看星标词时必须先调用。返回单词、释义、原句和复习情况。"
                + "scope=review 只返回已加入今日复习的词；可用 day 限定第几天（0=全部）。"
                + "未保存的文章提取结果不在这里。总数按词条统计，同词不同原句分别计数。"
                + "支持分页，next_offset 非空时还有更多词条；不能添加、删除或标记掌握。";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                "scope", Map.of("type", "string", "enum", List.of("all", "due", "review", "starred", "difficult"),
                        "description", "all 全部（默认）；due 已到复习时间；review 已加入今日复习；starred 已标星；difficult 最近反馈不会或模糊"),
                "day", Map.of("type", "integer", "minimum", 0, "maximum", 180,
                        "description", "仅 scope=review 时使用：0=全部，1..180=第几天"),
                "query", Map.of("type", "string", "maxLength", 80, "description", "可选：按单词或原形的部分拼写筛选"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20, "description", "每页最多词条数，默认 10"),
                "offset", Map.of("type", "integer", "minimum", 0, "maximum", 10000, "description", "翻页起点，默认 0，后续用 next_offset")));
    }

    @Override public String execute(Map<String, Object> args) {
        args = args == null ? Map.of() : args;
        for (String key : args.keySet()) {
            if (!Set.of("scope", "day", "query", "limit", "offset").contains(key))
                throw new IllegalArgumentException("不支持的参数；账号由登录身份确定");
        }
        String scope = Tool.strArg(args, "scope");
        if (scope.isEmpty()) scope = "all";
        if (!Set.of("all", "due", "review", "starred", "difficult").contains(scope))
            throw new IllegalArgumentException("scope 不正确");
        int day = Tool.intArg(args, "day", 0);
        if (day < 0 || day > VocabNotebook.MAX_PLAN_DAYS) throw new IllegalArgumentException("day 应为 0–180");
        if (day > 0 && !"review".equals(scope)) throw new IllegalArgumentException("day 只能配合 scope=review 使用");
        String query = Tool.strArg(args, "query").toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw new IllegalArgumentException("query 最多 80 字符");
        int limit = Tool.intArg(args, "limit", 10), offset = Tool.intArg(args, "offset", 0);
        if (limit < 1 || limit > 20 || offset < 0 || offset > 10000)
            throw new IllegalArgumentException("limit 应为 1–20，offset 应为 0–10000");

        // 每次现读，不缓存旧词表；只调用 list()/plan()，不触碰任何写入方法。
        var book = notebook.list();
        var plan = notebook.plan();
        if (day > plan.days()) throw new IllegalArgumentException("计划还没有第 " + day + " 天");
        List<VocabNotebook.Entry> matches = new ArrayList<>();
        int dueCount = 0, reviewCount = 0;
        for (var entry : book.entries()) {
            boolean due = entry.dueAt() <= book.now();
            boolean review = entry.queuedAt() > 0;
            if (due) dueCount++;
            if (review) reviewCount++;
            if (scope.equals("due") && !due) continue;
            if (scope.equals("review") && !review) continue;
            if (scope.equals("review") && day > 0 && entry.planDay() != day) continue;
            if (scope.equals("starred") && !entry.starred()) continue;
            if (scope.equals("difficult") && !Set.of("again", "hard").contains(lastRating(entry))) continue;
            if (!query.isEmpty() && !contains(entry.word().w(), query) && !contains(entry.word().lemma(), query)) continue;
            matches.add(entry);
        }
        if (scope.equals("review"))
            matches.sort(Comparator.comparingInt(VocabNotebook.Entry::planDay).thenComparing(VocabNotebook.Entry::id));
        else
            matches.sort(Comparator.comparingLong(VocabNotebook.Entry::dueAt).thenComparing(VocabNotebook.Entry::id));
        List<Map<String, Object>> words = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "当前登录账号的生词本（只读；同词不同原句分别计数）");
        result.put("now", Instant.ofEpochMilli(book.now()).toString());
        result.put("total_entries", book.entries().size());
        result.put("total_due", dueCount);
        result.put("total_review", reviewCount);
        result.put("plan_days", plan.days());
        result.put("scope", scope);
        if (day > 0) result.put("day", day);
        result.put("matched_entries", matches.size());
        result.put("offset", offset); result.put("words", words);
        for (int i = offset; i < matches.size() && words.size() < limit; i++) {
            words.add(word(matches.get(i), book.now()));
            // 留出分页字段的空间，避免 Agent 的通用长度截断把 JSON 切坏。
            if (json(result).length() > 4800) { words.remove(words.size() - 1); break; }
        }
        int next = offset + words.size();
        result.put("returned", words.size());
        result.put("next_offset", next < matches.size() ? next : null);
        return json(result);
    }

    private static Map<String, Object> word(VocabNotebook.Entry entry, long now) {
        var word = entry.word();
        String example = word.examples().isEmpty() ? "" : word.examples().get(0);
        var explanation = word.explanation();
        boolean applicable = explanation != null && (explanation.context() == null
                || explanation.context().isBlank() || explanation.context().equals(example));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("word", word.w());
        value.put("cn", cut(applicable && hasText(explanation.cn()) ? explanation.cn() : word.cn(), 140));
        value.put("en", cut(applicable && hasText(explanation.en()) ? explanation.en() : word.en(), 180));
        value.put("example", cut(example, 240));
        value.put("meaning_source", applicable ? cut(explanation.source(), 40) : "词典原文");
        value.put("due", entry.dueAt() <= now);
        value.put("due_at", Instant.ofEpochMilli(entry.dueAt()).toString());
        value.put("in_review", entry.queuedAt() > 0);
        value.put("plan_day", entry.planDay());
        value.put("starred", entry.starred());
        value.put("review_count", entry.reviewCount());
        value.put("streak", entry.streak());
        value.put("last_rating", lastRating(entry));
        return value;
    }

    private static String lastRating(VocabNotebook.Entry entry) {
        return entry.history().isEmpty() ? "unreviewed" : entry.history().get(entry.history().size() - 1).rating();
    }
    private static boolean contains(String text, String query) { return text != null && text.toLowerCase(Locale.ROOT).contains(query); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String cut(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("生词本结果无法编码", e); }
    }
}
