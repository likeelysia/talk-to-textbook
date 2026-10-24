package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VocabUpgradeTest {
    @TempDir Path temp;
    private VocabDictionary fake() {
        return new VocabDictionary("unused") {
            @Override public synchronized String[] lookup(String word) {
                return word.equals("unknown") ? null : new String[]{"", "中文 " + word, "", "cet4", "", word.equals("running") ? "0:run/1:i" : ""};
            }
        };
    }
    @Test void preservesCurlyContractionsCompoundsAndCounts() {
        var result = new VocabService(fake(),100).extract("Don’t stop. I’m running. state-of-the-art. Running!");
        assertThat(result.words()).extracting(VocabService.Word::w).containsExactly("don't","i'm","running","state-of-the-art","stop");
        var running = result.words().stream().filter(w->w.w().equals("running")).findFirst().orElseThrow();
        assertThat(running.count()).isEqualTo(2);
        assertThat(running.forms()).containsExactly("running","Running");
        assertThat(running.examples()).hasSize(2);
        assertThat(running.lemma()).isEqualTo("run");
    }
    @Test void distinguishesMissingEnglishFromMissingEntry() {
        var result = new VocabService(fake(),100).extract("hello unknown");
        assertThat(result.notFound()).isEqualTo(1);
        assertThat(result.words().get(0).lookupStatus()).isEqualTo("FOUND");
        assertThat(result.words().get(0).cn()).isNotBlank();
        assertThat(result.words().get(0).en()).isEmpty();
        assertThat(result.words().get(1).lookupStatus()).isEqualTo("NOT_FOUND");
    }
    @Test void dictionaryFailureDoesNotBecomeNotFound() {
        var dictionary = new VocabDictionary("unused") {
            @Override public synchronized String[] lookup(String word) throws SQLException { throw new SQLException("broken table"); }
        };
        assertThatThrownBy(()->new VocabService(dictionary,100).extract("hello"))
                .isInstanceOf(AgentException.class).hasMessageContaining("本次没有生成词表");
    }
    @Test void inputLimitsAndNoEnglishAreRejected() {
        var service = new VocabService(fake(),2);
        assertThatThrownBy(()->service.extract("a b c")).hasMessageContaining("最多提取");
        assertThatThrownBy(()->service.extract("这是中文 123")).hasMessageContaining("没有识别到");
        assertThatThrownBy(()->service.extract("a".repeat(100001))).hasMessageContaining("字符");
    }
    @Test void snapshotExportContainsOnlySelectedWordsAndMarksSources() throws Exception {
        var service = new VocabService(fake(),100);
        var word = service.extract("hello world").words().get(0).withExplanation(new VocabService.Explanation("你好","A word used when meeting someone.","AI 补充","simple","hello world"));
        byte[] bytes = service.docx(List.of(word),"en");
        try(var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = doc.getTables().get(0).getText();
            assertThat(text).contains("hello","AI 补充","A word used when meeting someone.","hello world");
            assertThat(doc.getTables().get(0).getRows()).hasSize(2);
        }
    }
    @Test void dictionaryIsReadOnlySupportsOldSchemaAndRecoversFromBadSchema() throws Exception {
        Path file=temp.resolve("dict.db");
        try(var conn=DriverManager.getConnection("jdbc:sqlite:"+file);var s=conn.createStatement()) {
            s.execute("create table stardict(word text primary key, phonetic text, translation text, definition text, tag text, pos text)");
            s.execute("insert into stardict values('Hello','','你好','','','')");
        }
        byte[] before=Files.readAllBytes(file);
        var dictionary=new VocabDictionary(file.toString());
        assertThat(dictionary.lookup("hello")[1]).isEqualTo("你好");
        dictionary.close();assertThat(Files.readAllBytes(file)).isEqualTo(before);
        Path bad=temp.resolve("bad.db");
        try(var conn=DriverManager.getConnection("jdbc:sqlite:"+bad);var s=conn.createStatement()){s.execute("create table other(x text)");}
        var broken=new VocabDictionary(bad.toString());
        assertThatThrownBy(()->broken.lookup("hello")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->broken.lookup("world")).isInstanceOf(SQLException.class);
        broken.close();
    }
}
