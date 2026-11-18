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
                + "按生词出题或写短文、复习到期/不会的词时必须先调用。返回单词、释义、原句和复习情况。"
                + "未保存的文章提取结果不在这里。总数按词条统计，同词不同原句分别计数。"
                + "支持分页，next_offset 非空时还有更多词条；不能添加、删除或标记掌握。";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                "scope", Map.of("type", "string", "enum", List.of("all", "due", "difficult"),
                        "description", "all 全部（默认）；due 当前已到复习时间；difficult 最近反馈不会或模糊"),
                "query", Map.of("type", "string", "maxLength", 80, "description", "可选：按单词或原形的部分拼写筛选"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20, "description", "每页最多词条数，默认 10"),
                "offset", Map.of("type", "integer", "minimum", 0, "maximum", 10000, "description", "翻页起点，默认 0，后续用 next_offset")));
    }

    @Override public String execute(Map<String, Object> args) {
        args = args == null ? Map.of() : args;
        for (String key : args.keySet()) {
            if (!Set.of("scope", "query", "limit", "offset").contains(key))
                throw new IllegalArgumentException("不支持的参数；账号由登录身份确定");
        }
        String scope = Tool.strArg(args, "scope");
        if (scope.isEmpty()) scope = "all";
        if (!Set.of("all", "due", "difficult").contains(scope)) throw new IllegalArgumentException("scope 不正确");
        String query = Tool.strArg(args, "query").toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw new IllegalArgumentException("query 最多 80 字符");
        int limit = Tool.intArg(args, "limit", 10), offset = Tool.intArg(args, "offset", 0);
        if (limit < 1 || limit > 20 || offset < 0 || offset > 10000)
            throw new IllegalArgumentException("limit 应为 1–20，offset 应为 0–10000");

        // 每次现读，不缓存旧词表；只调用 list()，不触碰任何写入方法。
        var book = notebook.list();
        List<VocabNotebook.Entry> matches = new ArrayList<>();
        int dueCount = 0;
        for (var entry : book.entries()) {
            boolean due = entry.dueAt() <= book.now();
            if (due) dueCount++;
            if (scope.equals("due") && !due) continue;
            if (scope.equals("difficult") && !Set.of("again", "hard").contains(lastRating(entry))) continue;
            if (!query.isEmpty() && !contains(entry.word().w(), query) && !contains(entry.word().lemma(), query)) continue;
            matches.add(entry);
        }
        matches.sort(Comparator.comparingLong(VocabNotebook.Entry::dueAt).thenComparing(VocabNotebook.Entry::id));
        List<Map<String, Object>> words = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "当前登录账号的生词本（只读；同词不同原句分别计数）");
        result.put("now", Instant.ofEpochMilli(book.now()).toString());
        result.put("total_entries", book.entries().size());
        result.put("total_due", dueCount);
        result.put("scope", scope); result.put("matched_entries", matches.size());
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
        return Map.of("word", word.w(),
                "cn", cut(applicable && hasText(explanation.cn()) ? explanation.cn() : word.cn(), 140),
                "en", cut(applicable && hasText(explanation.en()) ? explanation.en() : word.en(), 180),
                "example", cut(example, 240),
                "meaning_source", applicable ? cut(explanation.source(), 40) : "词典原文",
                "due", entry.dueAt() <= now, "due_at", Instant.ofEpochMilli(entry.dueAt()).toString(),
                "review_count", entry.reviewCount(), "streak", entry.streak(), "last_rating", lastRating(entry));
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
