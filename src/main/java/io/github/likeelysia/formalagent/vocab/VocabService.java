package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?");

    private final VocabDictionary dictionary;
    private final int maxWords;

    public VocabService(VocabDictionary dictionary,
                        @Value("${fa.vocab.max-words:5000}") int maxWords) {
        this.dictionary = dictionary;
        this.maxWords = maxWords;
    }

    /** 提取单词并查词典。 */
    public ExtractResult extract(String article) {
        if (article == null || article.isBlank()) {
            throw new AgentException("请先粘贴文章");
        }
        TreeSet<String> words = new TreeSet<>();
        Matcher m = WORD_PATTERN.matcher(article);
        while (m.find()) {
            words.add(m.group().toLowerCase());
        }
        if (words.size() > maxWords) {
            throw new AgentException("文章太长：提取到 " + words.size() + " 个单词，上限 " + maxWords);
        }

        List<Word> out = new ArrayList<>();
        int notFound = 0;
        for (String w : words) {
            String[] info;
            try {
                info = dictionary.lookup(w);
            } catch (Exception e) {
                // 词典文件缺失是“整页都用不了”，要直接报给用户；单个词查失败才降级成“未收录”
                if (e.getMessage() != null && e.getMessage().contains("词典文件不存在")) {
                    throw new AgentException(e.getMessage());
                }
                info = null;
            }
            if (info == null) {
                notFound++;
                out.add(new Word(w, "", "⚠ 未收录", "", "", ""));
            } else {
                out.add(new Word(w, info[0], info[1], info[2], tagToCn(info[3]), info[4]));
            }
        }
        return new ExtractResult(words.size(), notFound, out);
    }

    /** 生成 Word：mode = cn（中文释义）或 en（英英释义）。 */
    public byte[] docx(String article, String mode) throws Exception {
        ExtractResult result = extract(article);
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
            subRun.setText("共 " + result.total() + " 个单词  ·  ECDICT 词库");
            subRun.setFontSize(10);
            subRun.setColor("808080");

            String[] headers = en
                    ? new String[]{"序号", "单词", "音标", "英英释义"}
                    : new String[]{"序号", "单词", "音标", "中文释义", "英文释义", "考试标签"};
            XWPFTable table = doc.createTable(result.words().size() + 1, headers.length);
            table.setWidth("100%");
            for (int c = 0; c < headers.length; c++) {
                setCell(table, 0, c, headers[c], true, 11);
            }

            int row = 1;
            for (Word w : result.words()) {
                setCell(table, row, 0, String.valueOf(row), false, 10);
                setCell(table, row, 1, w.w(), true, 11);
                if ("⚠ 未收录".equals(w.cn())) {
                    for (int c = 2; c < headers.length; c++) setCell(table, row, c, "", false, 10);
                    setCell(table, row, 2, "⚠ 未收录", false, 10);
                } else if (en) {
                    setCell(table, row, 2, "/" + w.ph() + "/", false, 10);
                    setCell(table, row, 3, formatEnglishDef(w.en(), w.pos()), false, 10);
                } else {
                    setCell(table, row, 2, "/" + w.ph() + "/", false, 10);
                    setCell(table, row, 3, w.cn(), false, 10);
                    setCell(table, row, 4, w.en(), false, 10);
                    setCell(table, row, 5, w.tag(), false, 10);
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
            case "p" -> "preposition";
            case "c" -> "conjunction";
            case "i" -> "interjection";
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
        Pattern p = Pattern.compile("^(ad|n|v|s|a|r|j|p|c|i|m|u|t|x)\\.?\\s+(.*)$",
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

    public record Word(String w, String ph, String cn, String en, String tag, String pos) {
    }

    public record ExtractResult(int total, int notFound, List<Word> words) {
    }
}
