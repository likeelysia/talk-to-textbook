package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreMemoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void savesReadsAndInjectsAsSystemMessage() {
        CoreMemoryStore store = new CoreMemoryStore(dir.resolve("core.md").toString(), 100);

        store.save("我叫鹏哥\n在准备 Java 实习面试");

        assertThat(store.read()).isEqualTo("我叫鹏哥\n在准备 Java 实习面试");
        assertThat(store.asMessage()).isNotNull();
        assertThat(store.asMessage().content()).contains("核心档案").contains("Java 实习");
    }

    @Test
    void emptyFileMeansNoMessage() {
        CoreMemoryStore store = new CoreMemoryStore(dir.resolve("core.md").toString(), 100);
        assertThat(store.read()).isEmpty();
        assertThat(store.asMessage()).isNull();
    }

    @Test
    void rejectsOverLongTextInsteadOfSilentlyTruncating() {
        CoreMemoryStore store = new CoreMemoryStore(dir.resolve("core.md").toString(), 10);
        assertThatThrownBy(() -> store.save("12345678901"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("最多 10 字");
    }
}
