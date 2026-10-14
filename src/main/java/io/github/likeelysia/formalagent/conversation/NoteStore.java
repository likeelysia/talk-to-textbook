package io.github.likeelysia.formalagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 笔记本落盘:整本笔记就一个 JSON 文件。
 *
 * <pre>
 *   data/notes/notes.json   ← [{一条笔记}, {一条笔记}, ...]
 * </pre>
 *
 * <p><b>为什么是"一个文件整本"而不是"一个笔记一个文件"</b>:笔记本是**跨会话全局一本**的,
 * 一次打开要全列出来 —— 一个文件读一次最省事(规模也就几百条);
 * 而记忆是"按域隔离"的,才要一域一个文件。
 *
 * <p><b>并发</b>:一把锁把"读 → 改 → 写"整个包住(存笔记是 Web 请求,
 * 同一瞬间可能来好几个)。
 */
@Component
public class NoteStore {

    private static final Logger log = LoggerFactory.getLogger(NoteStore.class);

    private static final String DEFAULT_DIR = "data/notes";
    private static final String FILE_NAME = "notes.json";

    /** 整本笔记的文件内容。 */
    record NoteFile(List<Note> notes) {
    }

    private final ObjectMapper mapper;
    private final Path file;
    private final Object lock = new Object();

    @Autowired
    public NoteStore(ObjectMapper mapper, @Value("${fa.note.dir:data/notes}") String dir) {
        this(mapper, Path.of(dir == null || dir.isBlank() ? DEFAULT_DIR : dir, FILE_NAME));
    }

    /** 测试用:直接指定文件。 */
    public NoteStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    /** 全部笔记(新的在前)。 */
    public List<Note> list() {
        synchronized (lock) {
            List<Note> notes = new ArrayList<>(read());
            notes.sort((a, b) -> Long.compare(b.savedAt(), a.savedAt()));
            return List.copyOf(notes);
        }
    }

    public Optional<Note> find(String id) {
        synchronized (lock) {
            return read().stream().filter(n -> n.id().equals(id)).findFirst();
        }
    }

    /**
     * 追加一批笔记,**自动去重**(同一个会话里的同一句提问只留一条)。
     *
     * @return 真正新加进去的那些(被去重挡掉的不在里头)
     */
    public List<Note> addAll(List<Note> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        synchronized (lock) {
            List<Note> current = new ArrayList<>(read());
            Set<String> keys = new LinkedHashSet<>();
            for (Note n : current) {
                keys.add(n.dedupKey());
            }
            List<Note> added = new ArrayList<>();
            for (Note n : notes) {
                if (keys.add(n.dedupKey())) {
                    current.add(n);
                    added.add(n);
                }
            }
            if (!added.isEmpty()) {
                write(List.copyOf(current));
            }
            return List.copyOf(added);
        }
    }

    public boolean delete(String id) {
        synchronized (lock) {
            List<Note> current = new ArrayList<>(read());
            boolean removed = current.removeIf(n -> n.id().equals(id));
            if (removed) {
                write(List.copyOf(current));
            }
            return removed;
        }
    }

    // ==================== 内部 ====================

    private List<Note> read() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            NoteFile parsed = mapper.readValue(Files.readString(file), NoteFile.class);
            return parsed.notes() == null ? List.of() : parsed.notes();
        } catch (IOException e) {
            log.warn("笔记本读不出来({}):{}", file.getFileName(), e.getMessage());
            return List.of();                    // 坏文件不该让整个页面打不开
        }
    }

    private void write(List<Note> notes) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), new NoteFile(notes));
        } catch (IOException e) {
            throw new AgentException("笔记本保存失败:" + e.getMessage());
        }
    }
}
