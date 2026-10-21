package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 长期记忆落盘:一个「记忆域」一个 JSON 文件。
 *
 * <p><b>可靠性约定</b>:
 * <ul>
 *   <li>写入先写临时文件,再原子替换正式文件;替换前把上一份留成 {@code .bak};</li>
 *   <li>文件损坏时先尝试 {@code .bak},都坏才报错 —— <b>绝不把坏文件当空表再覆盖回去</b>;</li>
 *   <li>每个域有 {@code generation}:清空/单条删除后 +1。后台任务提交时带着旧代次,
 *       写入时在锁内校验,旧任务不能把删掉的记忆又写回来;</li>
 *   <li>{@code processedBatches} 记录"这批消息已经提炼过",重复执行不重复写。</li>
 * </ul>
 *
 * <p><b>为什么按记忆域分文件</b>:记忆按域隔离;共享时几个会话的 scopeId 指到同一个根,
 * 读写的是同一份文件,不存在"复制同步漏了"。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    /** 当前文件格式版本;以后加字段时用它做迁移判断。 */
    static final int FORMAT_VERSION = 1;

    private static final String SUFFIX = ".json";
    private static final String BACKUP_SUFFIX = ".json.bak";
    private static final String TEMP_SUFFIX = ".json.tmp";

    /** 合法的记忆域 id:纯 ASCII 字母数字下划线短横(顺带把 {@code ../} 路径穿越挡在门外)。 */
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,40}";

    /** 默认落盘目录。 */
    private static final String DEFAULT_DIR = "data/memory";

    /** 幂等批次标记最多留多少条(防止文件无限长)。 */
    private static final int MAX_BATCH_KEYS = 200;

    /** 一个记忆域的文件内容:格式版本 + 模型指纹 + 代次 + 记忆列表 + 已处理批次。 */
    record MemoryFile(int version, String model, long generation, List<MemoryEntry> entries,
                      List<String> processedBatches) {

        MemoryFile {
            version = version <= 0 ? FORMAT_VERSION : version;
            entries = entries == null ? List.of() : List.copyOf(entries);
            processedBatches = processedBatches == null ? List.of() : List.copyOf(processedBatches);
        }

        static MemoryFile empty(String model) {
            return new MemoryFile(FORMAT_VERSION, model, 0L, List.of(), List.of());
        }
    }

    /** 一次带代次校验的提交结果。 */
    record BatchCommit(List<MemoryEntry> entries, boolean stale, boolean duplicate) {
    }

    private final ObjectMapper mapper;
    private final Path dir;
    private final String model;

    private final Object lock = new Object();

    @Autowired
    public MemoryStore(ObjectMapper mapper, AppConfig config) {
        this(mapper, Path.of(dirOf(config)), modelOf(config));
    }

    /** 测试用:直接指定目录和模型指纹。 */
    public MemoryStore(ObjectMapper mapper, Path dir, String model) {
        this.mapper = mapper;
        this.dir = dir;
        this.model = model == null ? "default" : model;
    }

    /** 读一个记忆域的全部记忆(没有就是空表;损坏会抛异常,由调用方决定降级)。 */
    public List<MemoryEntry> list(String scopeId) {
        synchronized (lock) {
            return read(scopeId).entries();
        }
    }

    /** 当前代次;文件不存在 = 0。 */
    public long generation(String scopeId) {
        synchronized (lock) {
            return read(scopeId).generation();
        }
    }

    /**
     * "读 → 改 → 写"一把梭:治理接口(手动加/改/删)走它。
     *
     * <p>不暴露 {@code save(...)} 让调用方自己读改写 —— 那样并发时后写的会把先写的盖掉。
     */
    public List<MemoryEntry> update(String scopeId, UnaryOperator<List<MemoryEntry>> mutator) {
        synchronized (lock) {
            MemoryFile file = read(scopeId);
            List<MemoryEntry> updated = List.copyOf(mutator.apply(file.entries()));
            write(scopeId, file.generation(), file.processedBatches(), updated);
            return updated;
        }
    }

    /**
     * 和 {@link #update} 一样,但把代次 +1 —— 用于"删除"这类需要作废在途后台任务的动作。
     */
    public List<MemoryEntry> updateAndBumpGeneration(String scopeId,
                                                     UnaryOperator<List<MemoryEntry>> mutator) {
        synchronized (lock) {
            MemoryFile file = read(scopeId);
            List<MemoryEntry> updated = List.copyOf(mutator.apply(file.entries()));
            write(scopeId, file.generation() + 1, file.processedBatches(), updated);
            return updated;
        }
    }

    /**
     * 后台提炼的提交入口:在<b>同一把锁</b>里做"代次校验 + 批次幂等 + 读改写"。
     *
     * <p>旧代次返回 {@code stale=true}(调用方只推进水位,不写记忆);
     * 同一批次重复执行返回 {@code duplicate=true}(不重复写)。
     */
    public BatchCommit updateBatch(String scopeId, long expectedGeneration, String batchKey,
                                   UnaryOperator<List<MemoryEntry>> mutator) {
        synchronized (lock) {
            MemoryFile file = read(scopeId);
            if (file.generation() != expectedGeneration) {
                return new BatchCommit(file.entries(), true, false);
            }
            if (batchKey != null && file.processedBatches().contains(batchKey)) {
                return new BatchCommit(file.entries(), false, true);
            }
            List<MemoryEntry> updated = List.copyOf(mutator.apply(file.entries()));
            List<String> batches = new ArrayList<>(file.processedBatches());
            if (batchKey != null) {
                batches.add(batchKey);
                if (batches.size() > MAX_BATCH_KEYS) {
                    batches = new ArrayList<>(batches.subList(batches.size() - MAX_BATCH_KEYS, batches.size()));
                }
            }
            write(scopeId, file.generation(), batches, updated);
            return new BatchCommit(updated, false, false);
        }
    }

    /** 「最后被用到」的时间粒度:5 分钟内的重复召回不写盘 —— 召回在问答热路径上,不能为了几个时间戳狂写文件。 */
    private static final long TOUCH_GRANULARITY_MS = 5 * 60 * 1000L;

    /**
     * 记下"这几条刚被召回过"(淘汰时按"最久没用"先走)。
     *
     * <p>只在<b>真的变旧了</b>的时候才落盘:召回是每次提问都要走的路,不能每次都重写整个记忆域文件。
     */
    public void touch(String scopeId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        synchronized (lock) {
            MemoryFile file = read(scopeId);
            long now = System.currentTimeMillis();
            List<MemoryEntry> next = new ArrayList<>(file.entries().size());
            boolean dirty = false;
            for (MemoryEntry entry : file.entries()) {
                if (ids.contains(entry.id()) && now - entry.lastAccessAt() > TOUCH_GRANULARITY_MS) {
                    next.add(entry.withAccess(now));
                    dirty = true;
                } else {
                    next.add(entry);
                }
            }
            if (dirty) {
                write(scopeId, file.generation(), file.processedBatches(), List.copyOf(next));
            }
        }
    }

    /**
     * 清空一个记忆域(用户说"忘掉这些")。
     *
     * <p><b>不删文件</b>:保留格式版本、模型指纹和代次;代次 +1 让在途后台任务作废,
     * 否则它跑完会把刚清掉的记忆又写回来。文件里也不留旧条目。
     */
    public void clear(String scopeId) {
        synchronized (lock) {
            MemoryFile file = read(scopeId);
            write(scopeId, file.generation() + 1, List.of(), List.of());
        }
    }

    /** 兼容旧调用:语义等同 {@link #clear}。 */
    public void delete(String scopeId) {
        clear(scopeId);
    }

    // ==================== 内部 ====================

    private MemoryFile read(String scopeId) {
        Path file = pathOf(scopeId);
        if (!Files.exists(file)) {
            return MemoryFile.empty(model);
        }
        try {
            return parse(file, scopeId);
        } catch (RuntimeException | IOException e) {
            // 主文件坏了:先试备份;都坏才报错,绝不把空表写回去覆盖证据
            Path backup = backupOf(scopeId);
            if (Files.exists(backup)) {
                try {
                    MemoryFile recovered = parse(backup, scopeId);
                    log.warn("记忆文件 {} 损坏({}),已从备份 {} 恢复",
                            file.getFileName(), e.getMessage(), backup.getFileName());
                    return recovered;
                } catch (RuntimeException | IOException ignored) {
                    // 备份也坏:走下面统一报错
                }
            }
            log.error("记忆文件 {} 损坏且无可用备份:{}", file.getFileName(), e.getMessage());
            throw new AgentException("记忆文件损坏:" + file.getFileName(), e);
        }
    }

    private MemoryFile parse(Path file, String scopeId) throws IOException {
        MemoryFile parsed = mapper.readValue(Files.readString(file), MemoryFile.class);
        if (parsed.version() > FORMAT_VERSION) {
            throw new AgentException("记忆文件版本过新(" + parsed.version() + "),当前只支持 "
                    + FORMAT_VERSION + ":" + file.getFileName());
        }
        List<MemoryEntry> entries = parsed.entries();
        if (parsed.model() != null && !parsed.model().equals(model)) {
            log.info("记忆域 {} 的向量是另一个模型({})算的,本次不带向量用", scopeId, parsed.model());
            entries = entries.stream().map(e -> e.withText(e.text(), null)).toList();
        }
        return new MemoryFile(parsed.version(), model, parsed.generation(), entries,
                parsed.processedBatches());
    }

    /** 原子替换:先写临时文件,备份旧文件,再 move。中途失败时正式文件仍是旧的那份。 */
    private void write(String scopeId, long generation, List<String> batches,
                       List<MemoryEntry> entries) {
        Path file = pathOf(scopeId);
        Path temp = file.resolveSibling(file.getFileName().toString().replace(SUFFIX, TEMP_SUFFIX));
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), new MemoryFile(FORMAT_VERSION, model, generation,
                            entries, batches));
            if (Files.exists(file) && isParseable(file, scopeId)) {
                // 只有当前正式文件可解析时才覆盖备份;坏文件不能把上一份好备份挤掉
                Files.copy(file, backupOf(scopeId), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理临时文件失败不掩盖主错误
            }
            throw new AgentException("记忆保存失败:" + e.getMessage(), e);
        }
    }

    private boolean isParseable(Path file, String scopeId) {
        try {
            parse(file, scopeId);
            return true;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private Path backupOf(String scopeId) {
        return dir.resolve(scopeId + BACKUP_SUFFIX);
    }

    private Path pathOf(String scopeId) {
        if (scopeId == null || !scopeId.matches(ID_PATTERN)) {
            throw new AgentException("非法的记忆域 id:" + scopeId);
        }
        return dir.resolve(scopeId + SUFFIX);
    }

    private static String dirOf(AppConfig config) {
        return config == null || config.memory() == null || config.memory().longTerm() == null
                || config.memory().longTerm().dir() == null || config.memory().longTerm().dir().isBlank()
                ? DEFAULT_DIR
                : config.memory().longTerm().dir();
    }

    private static String modelOf(AppConfig config) {
        return config == null || config.embedding() == null ? "default" : config.embedding().model();
    }
}
