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
            List<Note> current = new ArrayList<>(readStrict(target));
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
            List<Note> current = new ArrayList<>(readStrict(target));
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
            List<Note> current = new ArrayList<>(readStrict(target));
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
     * <p>源或目标解析失败时严格中止，不把损坏文件当空库，不覆盖原件，也不写迁移标记。
     * 只有目标成功原子提交后才标记 v2；旧版本写下的 "1" 标记视为未验证，允许修复后重试。
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
                String content;
                try {
                    content = Files.readString(marker).strip();
                } catch (IOException e) {
                    throw new AgentException("迁移标记不可读,拒绝迁移:" + e.getMessage(), e);
                }
                if ("v2".equals(content)) return false;
                log.warn("发现旧版迁移标记({}),将按 v2 规则重新校验后迁移", content);
            }
            Path target = fileFor(ownerId);
            List<Note> legacy = readStrict(file);          // 坏源 → 抛错，不标成功
            List<Note> current = Files.exists(target)
                    ? new ArrayList<>(readStrict(target))  // 坏目标 → 抛错，不覆盖
                    : new ArrayList<>();
            if (legacy.isEmpty()) {
                markMigrated(marker);
                return false;
            }
            boolean changed = false;
            Set<String> ids = new LinkedHashSet<>();
            for (Note n : current) ids.add(n.id());
            for (Note n : legacy) {
                if (ids.add(n.id())) {
                    current.add(n);
                    changed = true;
                }
            }
            if (changed) {
                write(target, List.copyOf(current));
            }
            // 只有目标写入成功（或无需写）才标记 v2；标记失败下次可幂等重试。
            markMigrated(marker);
            log.info("旧笔记本迁移到 owner {}:legacy={} changed={}(原文件保留)",
                    ownerId, legacy.size(), changed);
            return changed;
        }
    }

    private static void markMigrated(Path marker) {
        try {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(marker.toAbsolutePath().getParent(),
                    marker.getFileName().toString(), ".tmp");
            Files.writeString(tmp, "v2");
            try {
                Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
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
        return read(target, false);
    }

    /** 写路径/迁移使用：解析或 IO 失败必须抛错，不能把坏文件当空库。 */
    private List<Note> readStrict(Path target) {
        return read(target, true);
    }

    private List<Note> read(Path target, boolean strict) {
        if (!Files.exists(target)) {
            return List.of();
        }
        try {
            NoteFile parsed = mapper.readValue(Files.readString(target), NoteFile.class);
            return parsed.notes() == null ? List.of() : parsed.notes();
        } catch (IOException e) {
            if (strict) {
                throw new AgentException("笔记本文件损坏或不可读,拒绝覆盖:" + target, e);
            }
            log.warn("笔记本读不出来({}):{}", target.getFileName(), e.getMessage());
            return List.of();                    // 页面读取允许友好降级;写路径走 strict
        }
    }

    private void write(List<Note> notes) {
        write(file, notes);
    }

    private void write(Path target, List<Note> notes) {
        Path tmp = null;
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), new NoteFile(notes));
            if (Files.exists(target)) {
                Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new AgentException("笔记本保存失败:" + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }
}
