package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 背词工具的业务层：抽词 → 查词典 → 组装结果 / 生成 Word。
 *
 * <p>术语说明（人话版）：
 * <ul>
 *   <li><b>抽词</b>：从文章里把所有英文单词抓出来，转小写、去重、按字母排序。</li>
 *   <li><b>英英模式</b>：把词典里的英文释义按词性（noun/verb/adjective…）分组并编号，
 *       看起来像剑桥词典的排版。</li>
 * </ul>
 */
@Service
public class VocabService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*");
    public static final int MAX_ARTICLE_CHARS = 100_000;

    private final VocabDictionary dictionary;
    private final int maxWords;

    public VocabService(VocabDictionary dictionary,
                        @Value("${fa.vocab.max-words:5000}") int maxWords) {
        this.dictionary = dictionary;
        this.maxWords = maxWords;
    }

    /** 提取单词并查词典。 */
    public ExtractResult extract(String article) {
        validateArticle(article);
        String text = normalize(article);
        Map<String, Occurrences> words = new java.util.TreeMap<>();
        Matcher m = WORD_PATTERN.matcher(text);
        while (m.find()) {
            String key = m.group().toLowerCase(Locale.ROOT);
            if (key.length() > 80) throw new AgentException("发现异常长的单词，请检查文章或网址内容");
            Occurrences occurrence = words.computeIfAbsent(key, k -> new Occurrences());
            occurrence.count++;
            if (occurrence.forms.size() < 16) occurrence.forms.add(m.group());
            if (occurrence.examples.size() < 3) occurrence.examples.add(sentence(text, m.start(), m.end()));
            if (words.size() > maxWords) throw new AgentException("单篇文章最多提取 " + maxWords + " 个不同单词");
        }
        if (words.isEmpty()) throw new AgentException("没有识别到英文单词，请检查文章内容");

        List<Word> out = new ArrayList<>();
        int notFound = 0;
        for (var item : words.entrySet()) {
            String w = item.getKey();
            Occurrences occurrence = item.getValue();
            String[] info;
            String lookupWord = w;
            String lemma = "";
            try {
                info = dictionary.lookup(w);
                if (info == null && w.contains("-")) {
                    String joined = w.replace("-", "");
                    info = dictionary.lookup(joined);
                    if (info != null) lookupWord = joined;
                }
                if (info != null && info.length > 5) {
                    for (String exchange : info[5].split("/")) {
                        if (exchange.startsWith("0:")) lemma = exchange.substring(2);
                    }
                }
            } catch (Exception e) {
                throw new AgentException("词典查询失败，请检查词典是否存在、完整且可读；本次没有生成词表", e);
            }
            if (info == null) {
                notFound++;
            }
            out.add(new Word(w, info == null ? "" : info[0], info == null ? "" : info[1],
                    info == null ? "" : info[2], info == null ? "" : tagToCn(info[3]), info == null ? "" : info[4],
                    List.copyOf(occurrence.forms), occurrence.count, List.copyOf(occurrence.examples), lemma,
                    lookupWord, info == null ? "NOT_FOUND" : "FOUND", null));
        }
        return new ExtractResult(words.size(), notFound, out);
    }

    /** 生成 Word：mode = cn（中文释义）或 en（英英释义）。 */
    public byte[] docx(String article, String mode) throws Exception {
        ExtractResult result = extract(article);
        return docx(result.words(), mode);
    }

    /** Export the reviewed snapshot, including selections and explicitly generated explanations. */
    public byte[] docx(List<Word> words, String mode) throws Exception {
        if (words == null || words.isEmpty() || words.size() > maxWords) throw new AgentException("请选择要导出的单词，最多 " + maxWords + " 个");
        boolean en = "en".equalsIgnoreCase(mode);

        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText(en ? "英文单词背诵表（英英释义）" : "英文单词背诵表");
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            titleRun.setFontFamily("微软雅黑");

            XWPFParagraph sub = doc.createParagraph();
            sub.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun subRun = sub.createRun();
            subRun.setText("共 " + words.size() + " 个单词 · 词典原文 / AI 补充 / 手动修改分别标注");
            subRun.setFontSize(10);
            subRun.setColor("808080");

            String[] headers = en
                    ? new String[]{"序号", "单词 / 原形", "英文解释", "中文辅助", "来源原句"}
                    : new String[]{"序号", "单词 / 原形", "音标", "中文释义", "考试标签", "来源原句"};
            XWPFTable table = doc.createTable(words.size() + 1, headers.length);
            table.setWidth("100%");
            for (int c = 0; c < headers.length; c++) {
                setCell(table, 0, c, headers[c], true, 11);
            }

            int row = 1;
            for (Word w : words) {
                validateWord(w);
                setCell(table, row, 0, String.valueOf(row), false, 10);
                setCell(table, row, 1, w.w() + (blank(w.lemma()) ? "" : " → " + w.lemma()), true, 11);
                if (en) {
                    String english = w.explanation() != null && !blank(w.explanation().en())
                            ? "[" + w.explanation().source() + "] " + w.explanation().en()
                            : blank(w.en()) ? "暂无英文释义" : "[词典原文] " + formatEnglishDef(w.en(), w.pos());
                    setCell(table, row, 2, english, false, 10);
                    setCell(table, row, 3, chinese(w), false, 10);
                    setCell(table, row, 4, String.join("\n", w.examples()), false, 10);
                } else {
                    setCell(table, row, 2, blank(w.ph()) ? "" : "/" + w.ph() + "/", false, 10);
                    setCell(table, row, 3, chinese(w), false, 10);
                    setCell(table, row, 4, w.tag(), false, 10);
                    setCell(table, row, 5, String.join("\n", w.examples()), false, 10);
                }
                row++;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void setCell(XWPFTable table, int row, int col, String text, boolean bold, int fontSize) {
        XWPFTableCell cell = table.getRow(row).getCell(col);
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        while (!p.getRuns().isEmpty()) p.removeRun(0);
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setFontFamily("微软雅黑");
    }

    // ==================== 英英释义（剑桥风格） ====================

    /** ECDICT 词性代码 → 英文全称。 */
    static String posName(String code) {
        if (code == null) return "";
        return switch (code.trim().toLowerCase()) {
            case "n" -> "noun";
            case "v" -> "verb";
            case "j", "a", "s" -> "adjective";
            case "r", "ad" -> "adverb";
            case "p", "pron" -> "pronoun";
            case "i", "prep" -> "preposition";
            case "c" -> "conjunction";
            case "interj" -> "interjection";
            case "m" -> "numeral";
            case "u" -> "auxiliary verb";
            case "t" -> "particle";
            default -> "";
        };
    }

    /** 从 pos 字段（如 "n:4/v:96"）取第一个词性。 */
    static String posFromField(String posField) {
        if (posField == null || posField.isBlank()) return "";
        String first = posField.split("/")[0];
        int i = first.indexOf(':');
        return posName(i > 0 ? first.substring(0, i) : first);
    }

    /** 把英文释义按词性分组并编号，例如 "adjective. 1) ... 2) ...; noun. 1) ..."。 */
    static String formatEnglishDef(String def, String posField) {
        if (def == null || def.isBlank()) return "";
        Pattern p = Pattern.compile("^(prep|pron|interj|ad|n|v|s|a|r|j|p|c|i|m|u|t|x)\\.?\\s+(.*)$",
                Pattern.CASE_INSENSITIVE);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String line : def.split("\\R")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = p.matcher(line);
            boolean hit = m.matches();
            String code = hit ? m.group(1).toLowerCase() : "";
            String body = hit ? m.group(2) : line;
            String pos = code.isEmpty() ? posFromField(posField) : posName(code);
            groups.computeIfAbsent(pos, k -> new ArrayList<>()).add(body);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            if (!e.getKey().isEmpty()) sb.append(e.getKey()).append(". ");
            List<String> items = e.getValue();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(i + 1).append(") ").append(items.get(i));
            }
        }
        return sb.toString();
    }

    /** 考试标签 → 中文。 */
    static String tagToCn(String tag) {
        if (tag == null || tag.isBlank()) return "";
        Map<String, String> map = new LinkedHashMap<>();
        map.put("zk", "中考");
        map.put("gk", "高考");
        map.put("cet4", "四级");
        map.put("cet6", "六级");
        map.put("ky", "考研");
        map.put("toefl", "托福");
        map.put("ielts", "雅思");
        map.put("gre", "GRE");
        StringBuilder sb = new StringBuilder();
        for (String t : tag.split("\\s+")) {
            String cn = map.get(t);
            if (cn != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(cn);
            }
        }
        return sb.toString();
    }

    // ==================== 返回结构 ====================

    public record Explanation(String cn, String en, String source, String level, String context) {
        public Explanation(String cn, String en, String source, String level) { this(cn, en, source, level, ""); }
    }

    public record Word(String w, String ph, String cn, String en, String tag, String pos,
                       List<String> forms, int count, List<String> examples, String lemma,
                       String lookupWord, String lookupStatus, Explanation explanation) {
        public Word {
            forms = forms == null ? List.of() : List.copyOf(forms);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
        public Word(String w, String ph, String cn, String en, String tag, String pos) {
            this(w, ph, cn, en, tag, pos, List.of(w), 1, List.of(), "", w, "FOUND", null);
        }
        public Word withExplanation(Explanation value) {
            return new Word(w, ph, cn, en, tag, pos, forms, count, examples, lemma, lookupWord, lookupStatus, value);
        }
    }

    public record ExtractResult(int total, int notFound, List<Word> words) {
    }

    public static void validateArticle(String article) {
        if (article == null || article.isBlank()) throw new AgentException("请先粘贴英文文章");
        if (article.length() > MAX_ARTICLE_CHARS) throw new AgentException("单篇文章最多 " + MAX_ARTICLE_CHARS + " 字符，请分段处理");
    }

    public static void validateWord(Word word) {
        if (word == null || word.w() == null || !WORD_PATTERN.matcher(word.w()).matches() || word.w().length() > 80)
            throw new AgentException("单词格式不正确");
        for (String field : new String[]{word.cn(), word.en(), word.ph(), word.pos(), word.tag(), word.lemma(), word.lookupWord()}) {
            if (field != null && field.length() > 12000) throw new AgentException("词条内容过长");
        }
        if (word.examples().size() > 3 || word.examples().stream().anyMatch(s -> s == null || s.length() > 400))
            throw new AgentException("来源原句过长，最多保存三条短句");
        if (word.forms().size() > 100 || word.forms().stream().anyMatch(s -> s == null || s.length() > 80))
            throw new AgentException("词形内容过长");
        if (word.explanation() != null) {
            for (String s : new String[]{word.explanation().cn(), word.explanation().en(), word.explanation().source(), word.explanation().level(), word.explanation().context()})
                if (s != null && s.length() > 2000) throw new AgentException("补充释义过长");
        }
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace('’', '\'').replace('‘', '\'')
                .replace('ʼ', '\'').replace('‐', '-').replace('‑', '-');
    }

    private static String chinese(Word w) {
        if (w.explanation() != null && !blank(w.explanation().cn())) return "[" + w.explanation().source() + "] " + w.explanation().cn();
        return blank(w.cn()) ? "暂无中文释义" : "[词典原文] " + w.cn();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String sentence(String text, int start, int end) {
        int left = start, right = end;
        while (left > 0 && start - left < 140 && ".!?\n".indexOf(text.charAt(left - 1)) < 0) left--;
        while (right < text.length() && right - end < 140 && ".!?\n".indexOf(text.charAt(right)) < 0) right++;
        if (right < text.length() && ".!?".indexOf(text.charAt(right)) >= 0) right++;
        return (left > 0 && start - left >= 140 ? "…" : "") + text.substring(left, right).strip()
                + (right < text.length() && right - end >= 140 ? "…" : "");
    }

    private static final class Occurrences {
        int count;
        final Set<String> forms = new LinkedHashSet<>();
        final Set<String> examples = new LinkedHashSet<>();
    }
}
