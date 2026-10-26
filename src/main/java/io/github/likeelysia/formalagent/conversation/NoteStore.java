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
    private static final String OWNER_PATTERN = "[A-Za-z0-9_-]{1,40}";

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

    public List<Note> list() {
        return list(null);
    }

    /** 只列这个 owner 的笔记；owner 由服务端认证身份传入，不能来自请求体。 */
    public List<Note> list(String ownerId) {
        synchronized (lock) {
            List<Note> notes = new ArrayList<>(read(fileFor(ownerId)));
            notes.sort((a, b) -> Long.compare(b.savedAt(), a.savedAt()));
            return List.copyOf(notes);
        }
    }

    public Optional<Note> find(String id) {
        return find(null, id);
    }

    public Optional<Note> find(String ownerId, String id) {
        synchronized (lock) {
            return read(fileFor(ownerId)).stream().filter(n -> n.id().equals(id)).findFirst();
        }
    }

    /**
     * 追加一批笔记,**自动去重**(同一个会话里的同一句提问只留一条)。
     *
     * @return 真正新加进去的那些(被去重挡掉的不在里头)
     */
    public List<Note> addAll(List<Note> notes) {
        return addAll(null, notes);
    }

    public List<Note> addAll(String ownerId, List<Note> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        synchronized (lock) {
            Path target = fileFor(ownerId);
            List<Note> current = new ArrayList<>(read(target));
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
                write(target, List.copyOf(current));
            }
            return List.copyOf(added);
        }
    }

    public boolean delete(String id) {
        return delete(null, id);
    }

    public boolean delete(String ownerId, String id) {
        synchronized (lock) {
            Path target = fileFor(ownerId);
            List<Note> current = new ArrayList<>(read(target));
            boolean removed = current.removeIf(n -> n.id().equals(id));
            if (removed) {
                write(target, List.copyOf(current));
            }
            return removed;
        }
    }

    /** 编辑一条笔记的问题/回答正文；owner 不符时返回 empty，不泄露他人笔记是否存在。 */
    public Optional<Note> update(String ownerId, String id, String question, List<Note.Answer> answers) {
        synchronized (lock) {
            Path target = fileFor(ownerId);
            List<Note> current = new ArrayList<>(read(target));
            for (int i = 0; i < current.size(); i++) {
                Note n = current.get(i);
                if (n.id().equals(id)) {
                    Note edited = new Note(n.id(), n.conversationId(),
                            question == null || question.isBlank() ? n.question() : question.strip(),
                            n.questionAt(), answers == null ? n.answers() : answers, n.savedAt());
                    current.set(i, edited);
                    write(target, List.copyOf(current));
                    return Optional.of(edited);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 把旧的全局 notes.json 迁给指定管理员 owner。
     *
     * <p>目标不存在：整本复制；目标已存在（例如首轮 bug 先建了 admin 目录）：
     * 只补 id 缺失的旧笔记，不覆盖已编辑内容。迁移完成后写标记，重复启动不会再把
     * 管理员已删除的旧笔记追加回来。
     */
    public boolean migrateLegacy(String ownerId) {
        synchronized (lock) {
            if (ownerId == null || ownerId.isBlank() || !Files.exists(file)) {
                return false;
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent == null) return false;
            Path marker = parent.resolve(".legacy-migrated");
            if (Files.exists(marker)) {
                return false;
            }
            Path target = fileFor(ownerId);
            List<Note> legacy = read(file);
            if (legacy.isEmpty()) {
                markMigrated(marker);
                return false;
            }
            boolean changed = false;
            if (!Files.exists(target)) {
                write(target, legacy);
                changed = true;
            } else {
                List<Note> current = new ArrayList<>(read(target));
                Set<String> ids = new LinkedHashSet<>();
                for (Note n : current) ids.add(n.id());
                for (Note n : legacy) {
                    if (ids.add(n.id())) {
                        current.add(n);
                        changed = true;
                    }
                }
                if (changed) write(target, List.copyOf(current));
            }
            markMigrated(marker);
            log.info("旧笔记本迁移到 owner {}:legacy={} changed={}(原文件保留)",
                    ownerId, legacy.size(), changed);
            return changed;
        }
    }

    private static void markMigrated(Path marker) {
        try {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Files.writeString(marker, "1");
        } catch (IOException e) {
            throw new AgentException("旧笔记迁移标记写入失败:" + e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private Path fileFor(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return file;
        }
        if (!ownerId.matches(OWNER_PATTERN)) {
            throw new AgentException("非法 ownerId");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) throw new AgentException("笔记目录不可用");
        return parent.resolve(ownerId).resolve(file.getFileName());
    }

    private List<Note> read() {
        return read(file);
    }

    private List<Note> read(Path target) {
        if (!Files.exists(target)) {
            return List.of();
        }
        try {
            NoteFile parsed = mapper.readValue(Files.readString(target), NoteFile.class);
            return parsed.notes() == null ? List.of() : parsed.notes();
        } catch (IOException e) {
            log.warn("笔记本读不出来({}):{}", target.getFileName(), e.getMessage());
            return List.of();                    // 坏文件不该让整个页面打不开
        }
    }

    private void write(List<Note> notes) {
        write(file, notes);
    }

    private void write(Path target, List<Note> notes) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(target.toFile(), new NoteFile(notes));
        } catch (IOException e) {
            throw new AgentException("笔记本保存失败:" + e.getMessage());
        }
    }
}
