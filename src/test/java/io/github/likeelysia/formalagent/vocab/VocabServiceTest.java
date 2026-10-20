package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VocabServiceTest {

    /** 用假词典代替 851 MB 的 stardict.db，测试只关心“抽词 + 组装”。 */
    private final VocabDictionary dictionary = new VocabDictionary("unused") {
        @Override
        public synchronized String[] lookup(String word) {
            return switch (word) {
                case "abandoned" -> new String[]{
                        "ә'bændәnd", "a. 被抛弃的", "s. forsaken\ns. free", "toefl", "j:100"};
                case "run" -> new String[]{
                        "rʌn", "n. 跑", "n. a score\nv. move fast", "zk gk", "n:15/v:85"};
                default -> null;
            };
        }
    };

    private final VocabService service = new VocabService(dictionary, 100);

    @Test
    void extractsLowercasesDedupesAndSorts() {
        VocabService.ExtractResult result = service.extract("The Run, the RUN! Abandoned?");

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.notFound()).isEqualTo(1);
        assertThat(result.words()).extracting(VocabService.Word::w)
                .containsExactly("abandoned", "run", "the");
        assertThat(result.words().get(0).tag()).isEqualTo("托福");
        assertThat(result.words().get(2).cn()).isEqualTo("⚠ 未收录");
    }

    @Test
    void formatsEnglishDefinitionsByPartOfSpeech() {
        assertThat(VocabService.formatEnglishDef("s. forsaken\ns. free", ""))
                .isEqualTo("adjective. 1) forsaken 2) free");
        assertThat(VocabService.formatEnglishDef("n. a score\nv. move fast", "n:15/v:85"))
                .isEqualTo("noun. 1) a score; verb. 1) move fast");
    }

    @Test
    void generatesDocxForBothModes() throws Exception {
        for (String mode : new String[]{"cn", "en"}) {
            byte[] bytes = service.docx("abandoned run", mode);
            assertThat(bytes).hasSizeGreaterThan(1000);
            assertThat(new String(bytes, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        }
    }
}
