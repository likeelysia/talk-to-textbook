package io.github.likeelysia.formalagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 会话落盘:一个会话一个 JSON 文件,外加一份 index.json(侧栏列表)。
 *
 * <pre>
 *   data/conversations/
 *     index.json      ← 只有"档案":id / 标题 / 时间 / 条数
 *     default.json    ← 默认会话(第一次打开就是它)
 *     3f9c1a....json  ← 新建的会话
 * </pre>
 *
 * <p><b>为什么用 JSON 而不是 {@code .bin}</b>:老的 {@code SessionStore} 用 Java 原生序列化,
 * 那是学"序列化机制"用的 —— 它把类的结构焊死在字节流里,加字段/改包名都容易读不出来,
 * 而且只有 Java 能读。会话和记忆是<b>要长期演进、要能被人看懂、将来还要搬进数据库</b>的数据,
 * 所以走 JSON。老的 {@code SessionStore} 保留不动(CLI 还在用)。
 *
 * <p><b>并发</b>:Web 是多线程的 —— 两个人(或两个标签页)同时发消息,就会有"同时读改写同一个文件"。
 * 这里用一把锁把"读 → 改 → 写"整个包住,宁可慢一点点也不丢消息。
 * (单机自用足够;真要多进程,那就该换数据库了。)
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final String INDEX_FILE = "index.json";
    private static final String SUFFIX = ".json";

    /** 合法的会话 id:纯 ASCII 字母数字下划线短横 —— 顺带把 {@code ../} 这种路径穿越挡在门外。 */
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,40}";

    private final ObjectMapper mapper;
    private final Path dir;

    /** 一把锁:所有"读改写"都在它里面完成。 */
    private final Object lock = new Object();

    @Autowired
    public ConversationStore(ObjectMapper mapper, AppConfig config) {
        this(mapper, Path.of(config.conversation().dir()));
    }

    /** 测试用:直接指定目录(临时目录),不背包也起得来。 */
    public ConversationStore(ObjectMapper mapper, Path dir) {
        this.mapper = mapper;
        this.dir = dir;
    }

    // ==================== 读 ====================

    /** 全部会话的档案,按"最近更新"在前排序。 */
    public List<ConversationMeta> list() {
        synchronized (lock) {
            return readIndex().conversations().stream()
                    .sorted(Comparator.comparingLong(ConversationMeta::updatedAt).reversed()
                            .thenComparing(ConversationMeta::id))
                    .toList();
        }
    }

    /** 读一个会话的全文。 */
    public Optional<Conversation> load(String id) {
        synchronized (lock) {
            return Optional.ofNullable(read(id));
        }
    }

    // ==================== 写 ====================

    /** 没有就建、有就返回(默认会话的 bootstrap 用它,所以反复调用也安全)。 */
    public Conversation ensure(String id, String title) {
        synchronized (lock) {
            Conversation existing = read(id);
            if (existing != null) {
                return existing;
            }
            Conversation created = Conversation.of(id, title);
            write(created);
            return created;
        }
    }

    /** 新建一个会话(id 自动生成)。 */
    public Conversation create(String title) {
        synchronized (lock) {
            Conversation created = Conversation.of(newId(), title);
            write(created);
            return created;
        }
    }

    /**
     * "读 → 改 → 写"一把梭:改会话的唯一入口。
     *
     * <p>为什么不暴露 {@code save(Conversation)} 让调用方自己读改写?
     * 那样两个并发请求会互相覆盖(经典的 lost update)。锁在里面,调用方就不用操心。
     */
    public Conversation update(String id, UnaryOperator<Conversation> mutator) {
        synchronized (lock) {
            Conversation existing = read(id);
            if (existing == null) {
                throw new AgentException("会话不存在:" + id);
            }
            Conversation updated = mutator.apply(existing);
            write(updated);
            return updated;
        }
    }

    /** 删除会话(文件 + 档案一起删)。默认会话不允许删。 */
    public void delete(String id) {
        synchronized (lock) {
            pathOf(id);                                   // 先验 id 合法(顺带挡路径穿越)
            if (Conversation.DEFAULT_ID.equals(id)) {
                throw new AgentException("默认对话不能删除");
            }
            try {
                Files.deleteIfExists(pathOf(id));
            } catch (IOException e) {
                throw new AgentException("删除会话失败:" + e.getMessage());
            }
            writeIndexFile(readIndex().conversations().stream()
                    .filter(m -> !m.id().equals(id))
                    .toList());
        }
    }

    // ==================== 内部:单文件读写 ====================

    private Conversation read(String id) {
        Path file = pathOf(id);
        if (!Files.exists(file)) {
            return null;                                  // 没档可读,是正常情况
        }
        try {
            return mapper.readValue(Files.readString(file), Conversation.class);
        } catch (IOException e) {
            log.warn("会话文件读不出来({}):{}", file.getFileName(), e.getMessage());
            return null;                                  // 坏文件不该让整个侧栏打不开
        }
    }

    private void write(Conversation conversation) {
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(pathOf(conversation.id()).toFile(), conversation);
            updateIndex(conversation.meta());
        } catch (IOException e) {
            throw new AgentException("会话保存失败:" + e.getMessage());
        }
    }

    // ==================== 内部:index.json ====================

    private ConversationIndex readIndex() {
        Path file = dir.resolve(INDEX_FILE);
        if (!Files.exists(file)) {
            return rebuildIndex();                        // 第一次运行 / 被人删了 → 扫盘重建
        }
        try {
            return mapper.readValue(Files.readString(file), ConversationIndex.class);
        } catch (IOException e) {
            log.warn("index.json 读不出来({}),改为扫盘重建", e.getMessage());
            return rebuildIndex();
        }
    }

    /** 把某个会话的档案写进 index(有就替换、没有就追加)。 */
    private void updateIndex(ConversationMeta meta) {
        List<ConversationMeta> metas = new ArrayList<>(readIndex().conversations());
        metas.removeIf(m -> m.id().equals(meta.id()));
        metas.add(meta);
        writeIndexFile(metas);
    }

    /** 兜底:index.json 没了/坏了,就扫目录里所有会话文件重建一份。 */
    private ConversationIndex rebuildIndex() {
        List<ConversationMeta> metas = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(SUFFIX) || name.equals(INDEX_FILE)) {
                        continue;
                    }
                    Conversation found = read(name.substring(0, name.length() - SUFFIX.length()));
                    if (found != null) {
                        metas.add(found.meta());
                    }
                }
            } catch (IOException e) {
                log.warn("扫盘重建索引失败:{}", e.getMessage());
            }
        }
        writeIndexFile(metas);
        return new ConversationIndex(metas);
    }

    private void writeIndexFile(List<ConversationMeta> metas) {
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve(INDEX_FILE).toFile(), new ConversationIndex(metas));
        } catch (IOException e) {
            log.warn("写 index.json 失败:{}", e.getMessage());   // 列表坏掉不等于聊天不能用
        }
    }

    // ==================== 内部:小工具 ====================

    private Path pathOf(String id) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new AgentException("非法的会话 id:" + id);
        }
        return dir.resolve(id + SUFFIX);
    }

    private static String newId() {
        return "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
