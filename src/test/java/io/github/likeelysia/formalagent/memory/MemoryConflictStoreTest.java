package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryConflictStoreTest {

    @TempDir
    Path tmp;

    @Test
    void recordsDedupesAndDismisses() {
        MemoryConflictStore store = new MemoryConflictStore(
                new ObjectMapper(), tmp.resolve("conflicts.json"));

        store.addAll("scope1", List.of("用户要就业"), "c1");
        store.addAll("scope1", List.of("用户要就业"), "c1");   // 同一句不重复提示

        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).candidateText()).isEqualTo("用户要就业");

        assertThat(store.dismiss(store.list().get(0).id())).isTrue();
        assertThat(store.list()).isEmpty();
        assertThat(store.dismiss("不存在的 id")).isFalse();
    }
}
