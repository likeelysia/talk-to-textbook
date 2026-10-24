package io.github.likeelysia.formalagent.vocab;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 背词工具的词典访问层。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>只开一条连接</b>：旧版每查一个词就新开一次 SQLite 连接，1000 个词就是 1000 次连接，
 *       文章一长就慢；这里复用一条只读连接 + 预编译语句。</li>
 *   <li><b>懒加载</b>：第一次查词才打开词典。词典文件缺失不影响应用启动，只会在页面报错。</li>
 *   <li><b>词典外置</b>：851 MB 的 stardict.db 不进 jar，路径由 {@code fa.vocab.dict-path} 配置。</li>
 * </ul>
 */
@Component
public class VocabDictionary {

    private final Path path;
    private Connection conn;
    private PreparedStatement lookup;
    private boolean hasExchange;

    public VocabDictionary(@Value("${fa.vocab.dict-path:dict/stardict.db}") String dictPath) {
        this.path = Path.of(dictPath);
    }

    /** 查一个单词：返回 {音标, 中文释义, 英文释义, 考试标签, 词性字段}，未收录返回 null。 */
    public synchronized String[] lookup(String word) throws SQLException {
        ensureOpen();
        lookup.setString(1, word);
        try (ResultSet rs = lookup.executeQuery()) {
            if (rs.next()) {
                return new String[]{
                        nz(rs.getString(1)),
                        clean(nz(rs.getString(2))),
                        clean(nz(rs.getString(3))),
                        nz(rs.getString(4)),
                        nz(rs.getString(5)),
                        hasExchange ? nz(rs.getString(6)) : ""
                };
            }
        }
        return null;
    }

    private void ensureOpen() throws SQLException {
        if (conn != null && !conn.isClosed() && lookup != null) {
            return;
        }
        if (!Files.isRegularFile(path)) {
            throw new SQLException("词典文件不存在：" + path.toAbsolutePath()
                    + "（请把 stardict.db 放到该路径，或配置 fa.vocab.dict-path / 环境变量 FA_VOCAB_DICT）");
        }
        close();
        try {
            // SQLite URI enforces read-only access even when the process can write to dict/.
            conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().toUri() + "?mode=ro");
            try (var statement = conn.createStatement(); var columns = statement.executeQuery("PRAGMA table_info(stardict)")) {
                hasExchange = false;
                while (columns.next()) {
                    if ("exchange".equals(columns.getString("name"))) hasExchange = true;
                }
            }
            lookup = conn.prepareStatement("SELECT phonetic, translation, definition, tag, pos"
                    + (hasExchange ? ", exchange" : "") + " FROM stardict WHERE word = ? COLLATE NOCASE");
        } catch (SQLException e) {
            close();
            throw new SQLException("词典无法读取，请检查词典文件与表结构", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 清理 HTML 标签和多余空白，但保留换行（英英释义按行分词性）。 */
    private static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replaceAll("[^\\S\\r\\n]+", " ");
        s = s.replaceAll("\\r\\n?", "\n");
        return s.trim();
    }

    @PreDestroy
    public synchronized void close() {
        try {
            if (lookup != null) lookup.close();
        } catch (SQLException ignored) {
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
        lookup = null;
        conn = null;
    }
}
