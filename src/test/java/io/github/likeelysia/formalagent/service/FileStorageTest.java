package io.github.likeelysia.formalagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 上传落盘的"安全边界"测试:重点压测路径遍历、非法扩展名这些恶意输入。 */
class FileStorageTest {

    @TempDir Path tmp;

    private FileStorage storage() {
        AppConfig config = new AppConfig(false, null, null, null, null,
                new AppConfig.Upload(tmp.toString()), null, null);
        return new FileStorage(config);
    }

    @Test
    @DisplayName("路径遍历被砍掉:只取文件名,不残留 ../ 或 /")
    void blocksPathTraversal() {
        String safe = FileStorage.sanitize("../../../etc/passwd.txt");

        assertFalse(safe.contains("/"), "不应残留目录分隔符");
        assertFalse(safe.contains(".."), "不应残留 ..");
        assertTrue(safe.endsWith("-passwd.txt"), "只该留下文件名");
    }

    @Test
    @DisplayName("Windows 绝对路径也只取文件名")
    void stripsAbsolutePath() {
        String safe = FileStorage.sanitize("C:\\Users\\29458\\物理.pdf");

        assertTrue(safe.endsWith("-物理.pdf"));
        assertFalse(safe.contains(":"));
    }

    @Test
    @DisplayName("扩展名白名单:可执行 / 无扩展名 / 空名字一律拒绝")
    void rejectsDisallowedNames() {
        assertThrows(AgentException.class, () -> FileStorage.sanitize("evil.exe"));
        assertThrows(AgentException.class, () -> FileStorage.sanitize("noext"));
        assertThrows(AgentException.class, () -> FileStorage.sanitize("../"));
        assertThrows(AgentException.class, () -> FileStorage.sanitize(""));
    }

    @Test
    @DisplayName("落盘成功:文件在 uploads 目录内、名字被唯一化、内容正确")
    void storesUploadedFile() throws Exception {
        FileStorage storage = storage();

        Path saved = storage.store("笔记.md",
                new ByteArrayInputStream("位移是矢量".getBytes(StandardCharsets.UTF_8)));

        assertTrue(Files.exists(saved));
        assertTrue(saved.startsWith(tmp.toAbsolutePath().normalize()), "必须落在指定目录内");
        assertTrue(saved.getFileName().toString().endsWith("-笔记.md"));
        assertEquals("位移是矢量", Files.readString(saved, StandardCharsets.UTF_8));
    }
}
