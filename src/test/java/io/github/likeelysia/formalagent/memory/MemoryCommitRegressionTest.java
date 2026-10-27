package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** 把已验收的 OwnerLockProbe/RewriteEmbeddingProbe 固定为项目内回归。 */
class MemoryCommitRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ownerCommitScenariosRemainValid() throws Exception {
        Path root = Files.createTempDirectory("owner-lock-test");
        OwnerLockProbe.main(new String[] {root.toString()});
        JsonNode results = MAPPER.readTree(root.resolve("owner-lock-results.json").toFile());
        assertThat(StreamSupport.stream(results.spliterator(), false))
                .allMatch(n -> n.path("passed").asBoolean(),
                        "每个 owner 锁场景都必须通过");
    }

    @Test
    void rewrittenEmbeddingStaysOutsideOwnerAndStorageLocks() throws Exception {
        Path root = Files.createTempDirectory("rewrite-lock-test");
        RewriteEmbeddingProbe.main(new String[] {root.toString()});
        JsonNode result = MAPPER.readTree(root.resolve("rewrite-results.json").toFile());
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("rewritten_text_and_vector_saved").asBoolean()).isTrue();
    }
}
