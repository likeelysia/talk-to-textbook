package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <pre>
 *   data/memory/
 *     default.json      ← 「默认对话」这个记忆域攒下来的记忆
 *     c3f91a....json    ← 某个会话自己的记忆域
 *     shared-java.json  ← 以后几个会话共享同一份记忆(scopeId 指过来就行)
 * </pre>
 *
 * <p><b>为什么按记忆域分文件</b>:记忆是"按域隔离"的 —— 换会话不该串味儿,
 * 想共享的时候把几个会话的 scopeId 指到同一个文件即可,不用做任何"同步复制"。
 *
 * <p><b>文件里记了模型指纹</b>:向量空间由 embedding 模型决定,
 * 模型一换,老向量和新的就不在同一个坐标系里了 → 读取时直接把它们清掉
 * (退化成"按时间取最近几条"),而不是拿两套坐标硬比。
 *
 * <p><b>并发</b>:和 {@link io.github.likeelysia.formalagent.conversation.ConversationStore}
 * 一样,一把锁把"读 → 改 → 写"整个包住(提炼在后台线程跑,和 Web 请求会碰头)。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private static final String SUFFIX = ".json";

    /** 合法的记忆域 id:纯 ASCII 字母数字下划线短横(顺带把 {@code ../} 路径穿越挡在门外)。 */
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,40}";

    /** 默认落盘目录。 */
    private static final String DEFAULT_DIR = "data/memory";

    /** 一个记忆域的文件内容:模型指纹 + 记忆列表。 */
    record MemoryFile(String model, List<MemoryEntry> entries) {
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

    /** 读一个记忆域的全部记忆(没有就是空表)。 */
    public List<MemoryEntry> list(String scopeId) {
        synchronized (lock) {
            return read(scopeId);
        }
    }

    /**
     * "读 → 改 → 写"一把梭:改记忆的唯一入口。
     *
     * <p>不暴露 {@code save(...)} 让调用方自己读改写 —— 那样并发时后写的会把先写的盖掉。
     *
     * @return 落盘后的新列表
     */
    public List<MemoryEntry> update(String scopeId, UnaryOperator<List<MemoryEntry>> mutator) {
        synchronized (lock) {
            List<MemoryEntry> updated = List.copyOf(mutator.apply(read(scopeId)));
            write(scopeId, updated);
            return updated;
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
            List<MemoryEntry> entries = read(scopeId);
            long now = System.currentTimeMillis();
            List<MemoryEntry> next = new ArrayList<>(entries.size());
            boolean dirty = false;
            for (MemoryEntry entry : entries) {
                if (ids.contains(entry.id()) && now - entry.lastAccessAt() > TOUCH_GRANULARITY_MS) {
                    next.add(entry.withAccess(now));
                    dirty = true;
                } else {
                    next.add(entry);
                }
            }
            if (dirty) {
                write(scopeId, List.copyOf(next));
            }
        }
    }

    /** 清空一个记忆域(治理用:用户说"忘掉这些"就得真忘掉)。 */
    public void delete(String scopeId) {
        synchronized (lock) {
            try {
                Files.deleteIfExists(pathOf(scopeId));
            } catch (IOException e) {
                throw new AgentException("删除记忆失败:" + e.getMessage());
            }
        }
    }

    // ==================== 内部 ====================

    private List<MemoryEntry> read(String scopeId) {
        Path file = pathOf(scopeId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            MemoryFile parsed = mapper.readValue(Files.readString(file), MemoryFile.class);
            List<MemoryEntry> entries = parsed.entries() == null ? List.of() : parsed.entries();
            if (parsed.model() != null && !parsed.model().equals(model)) {
                log.info("记忆域 {} 的向量是另一个模型({})算的,本次不带向量用", scopeId, parsed.model());
                return entries.stream().map(e -> e.withText(e.text(), null)).toList();
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            log.warn("记忆文件读不出来({}):{}", file.getFileName(), e.getMessage());
            return List.of();                               // 坏文件不该让聊天打不开
        }
    }

    private void write(String scopeId, List<MemoryEntry> entries) {
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(pathOf(scopeId).toFile(), new MemoryFile(model, entries));
        } catch (IOException e) {
            throw new AgentException("记忆保存失败:" + e.getMessage());
        }
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
