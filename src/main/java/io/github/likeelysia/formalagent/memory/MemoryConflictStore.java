package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 核心档案冲突的待确认记录。
 *
 * <p>只是"提示"，不是"改写":这里永远不碰 {@link CoreMemoryStore}。
 * 用户在界面上看到提示后，自己决定改档案还是忽略。
 */
@Component
public class MemoryConflictStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryConflictStore.class);

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_CONFLICTS = 100;

    record ConflictFile(int version, List<MemoryConflict> conflicts) {
        ConflictFile {
            version = version <= 0 ? FORMAT_VERSION : version;
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    private final ObjectMapper mapper;
    private final Path path;
    private final Object lock = new Object();

    @Autowired
    public MemoryConflictStore(ObjectMapper mapper,
                               @Value("${fa.memory.conflicts-path:data/memory/conflicts.json}") String file) {
        this(mapper, Path.of(file));
    }

    /** 测试用。 */
    public MemoryConflictStore(ObjectMapper mapper, Path path) {
        this.mapper = mapper;
        this.path = path;
    }

    /** 待确认的冲突(新的在前)。 */
    public List<MemoryConflict> list() {
        synchronized (lock) {
            return read().conflicts().stream()
                    .filter(MemoryConflict::active)
                    .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
                    .toList();
        }
    }

    public int count() {
        return list().size();
    }

    /**
     * 记录一批冲突;同一记忆域 + 同一句候选只留一条(避免每次提炼重复提示)。
     */
    public void addAll(String scopeId, List<String> candidates, String conversationId) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ConflictFile file = read();
            List<MemoryConflict> next = new ArrayList<>(file.conflicts());
            for (String candidate : candidates) {
                String text = candidate == null ? "" : candidate.strip();
                if (text.isBlank() || contains(next, scopeId, text)) {
                    continue;
                }
                next.add(MemoryConflict.of(scopeId, text, conversationId));
            }
            if (next.size() > MAX_CONFLICTS) {
                next = new ArrayList<>(next.subList(next.size() - MAX_CONFLICTS, next.size()));
            }
            write(List.copyOf(next));
        }
    }

    /** 忽略一条。 */
    public boolean dismiss(String id) {
        synchronized (lock) {
            List<MemoryConflict> next = new ArrayList<>();
            boolean hit = false;
            for (MemoryConflict conflict : read().conflicts()) {
                if (conflict.id().equals(id) && conflict.active()) {
                    next.add(conflict.dismissed());
                    hit = true;
                } else {
                    next.add(conflict);
                }
            }
            if (hit) {
                write(List.copyOf(next));
            }
            return hit;
        }
    }

    private static boolean contains(List<MemoryConflict> conflicts, String scopeId, String text) {
        return conflicts.stream().anyMatch(c -> c.active()
                && c.scopeId().equals(scopeId)
                && c.candidateText().equals(text));
    }

    private ConflictFile read() {
        if (!Files.isRegularFile(path)) {
            return new ConflictFile(FORMAT_VERSION, List.of());
        }
        try {
            ConflictFile parsed = mapper.readValue(Files.readString(path), ConflictFile.class);
            return new ConflictFile(FORMAT_VERSION, parsed.conflicts());
        } catch (IOException e) {
            log.warn("冲突记录读不出来,先当空表:{}", e.getMessage());
            return new ConflictFile(FORMAT_VERSION, List.of());
        }
    }

    private void write(List<MemoryConflict> conflicts) {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), new ConflictFile(FORMAT_VERSION, conflicts));
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不掩盖主错误
            }
            throw new AgentException("冲突记录保存失败:" + e.getMessage(), e);
        }
    }
}
