package io.github.likeelysia.formalagent.conversation;

import io.github.likeelysia.formalagent.exception.AgentException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话业务:列表 / 新建 / 改名 / 删除 / 记一轮问答。
 *
 * <p>它站在 {@link ConversationStore}(只管"怎么存")和 Controller(只管"怎么收请求")之间,
 * 把<b>规则</b>收在这里:默认会话必须先存在、标题不能空、默认会话不许删、
 * 空白 id 一律当默认会话(向后兼容:老前端不带 conversationId 也能用)。
 */
@Component
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationStore store;

    public ConversationService(ConversationStore store) {
        this.store = store;
    }

    /** 启动就把默认会话兜住 —— 用户第一次打开,"默认对话"已经在那儿了。 */
    @PostConstruct
    public void init() {
        Conversation created = ensureDefault();
        log.info("会话目录就绪,默认会话:{}", created.id());
    }

    public Conversation ensureDefault() {
        return store.ensure(Conversation.DEFAULT_ID, Conversation.DEFAULT_TITLE);
    }

    public List<ConversationMeta> list() {
        ensureDefault();
        return store.list();
    }

    public Conversation get(String id) {
        String resolved = resolve(id);
        return store.load(resolved)
                .orElseThrow(() -> new AgentException("会话不存在:" + resolved));
    }

    public ConversationMeta create(String title) {
        return store.create(title).meta();
    }

    public ConversationMeta rename(String id, String title) {
        if (title == null || title.isBlank()) {
            throw new AgentException("标题不能为空");
        }
        return store.update(resolve(id), c -> c.renamed(title.strip())).meta();
    }

    public void delete(String id) {
        store.delete(resolve(id));
    }

    /**
     * 解析 + 校验会话 id(不存在就直接报错)。
     *
     * <p>给"先把 id 验干净、再去花钱调模型"的调用方用 —— 别等 LLM 跑完 3 秒钟
     * 才发现会话 id 是坏的,那 token 白烧了。
     */
    public String requireExisting(String id) {
        String resolved = resolve(id);
        if (store.load(resolved).isEmpty()) {
            throw new AgentException("会话不存在:" + resolved + "(请刷新页面重新选择会话)");
        }
        return resolved;
    }

    /**
     * 把一轮问答记进会话:先用户、后助手,顺带"首条提问自动当标题"。
     *
     * @param conversationId 可为空 → 落到默认会话
     * @param question       存用户<b>原本</b>那句话(图片链路上是"用户的问题/几张图",不是拼给模型的长文)
     * @param answer         助手回答
     * @param trace          Agent 这一轮调过的工具(可为 null)
     */
    public Conversation record(String conversationId, String question, String answer, List<String> trace) {
        String id = resolve(conversationId);
        return store.update(id, conversation -> conversation
                .appended(ChatMessage.user(question))
                .appended(ChatMessage.assistant(answer, trace))
                .autoTitled());
    }

    /**
     * 记下本会话的滚动摘要(短期记忆压缩出来的东西)。
     *
     * @param covered 摘要已经“吃进去”的前多少条消息。水位只能前进、不能后退 ——
     *                推错了就等于历史凭空消失。
     */
    public Conversation applySummary(String id, String summary, int covered) {
        return store.update(resolve(id), c -> c.withSummary(summary, Math.max(covered, c.summarizedCount())));
    }

    /**
     * 记下长期记忆提炼到哪儿了(前多少条消息已经送去提炼过)。
     *
     * <p>和 {@link #applySummary} 一个道理:水位<b>只进不退</b> ——
     * 推过头,那几轮对话就再也不会被提炼了(提炼不着,也补不回来)。
     *
     * <p>提炼在后台线程跑,所以这里直接走 store 的锁:读到的是“此刻”的会话,
     * 不会把 Web 请求刚写进去的消息盖掉。
     */
    public Conversation markMemorized(String id, int covered) {
        return store.update(resolve(id), c -> c.withMemoryCount(Math.max(covered, c.memoryCount())));
    }

    // ==================== 记忆域(共享 / 拆开) ====================

    /** 记忆域最多套几层。正常只有两层(A → 根),留出富余只是为了能识别出环。 */
    private static final int MAX_SCOPE_DEPTH = 10;

    /**
     * 解析这个会话“实际读写哪一份长期记忆”。
     *
     * <p>默认每个会话自己一份({@code scopeId == 自己});把若干会话的 scopeId 指成同一个,它们就共用一份。
     * 这里做的是“顺着指针走到根”:A 指 B、B 又指 C 时,A 和 B 实际读写的都是 C 那份 ——
     * 不会出现“以为在共享、其实一个读写 B、另一个读写 C”的错位。
     *
     * <p>指到一个<b>已经被删掉</b>的会话时,就停在那个名字上:记忆文件还在,数据不丢,
     * 也不会串到别人家(几个“共享到它”的会话依然共用那一份)。
     */
    public String memoryScopeOf(String id) {
        String cur = requireExisting(id);
        for (int depth = 0; depth < MAX_SCOPE_DEPTH; depth++) {
            String scope = store.load(cur).map(Conversation::scopeId).orElse(cur);
            if (scope.equals(cur)) {
                return cur;
            }
            cur = scope;
        }
        throw new AgentException("记忆域嵌套太深(可能存在环):" + id);
    }

    /**
     * 让一个会话“加入”另一个会话的记忆域(共享),或指回自己(拆开)。
     *
     * <p><b>共享 = 指向同一个存储槽,不做复制同步</b> —— 所以两边看到的就是同一份,
     * 删一条两边都少一条,不存在“同步漏了”的中间态。
     *
     * <p>存的是<b>根</b>(目标所在那一组最终指的那个 id):这样 A→B、B→C 这种长链不会越接越长,
     * 也不会成环。
     *
     * @param id       要改的会话
     * @param targetId 目标会话 id;null / 空白 / 就是自己 → 拆回独立(自己一份空记忆)
     */
    public ConversationMeta shareMemory(String id, String targetId) {
        String me = requireExisting(id);
        if (targetId == null || targetId.isBlank() || targetId.strip().equals(me)) {
            return store.update(me, c -> c.withScope(me)).meta();
        }
        String root = memoryScopeOf(targetId.strip());     // 目标不存在 / id 非法 → 这里报错
        return store.update(me, c -> c.withScope(root)).meta();
    }

    /** null / 空白 → 默认会话。 */
    private String resolve(String id) {
        if (id == null || id.isBlank()) {
            ensureDefault();
            return Conversation.DEFAULT_ID;
        }
        String clean = id.strip();
        if (Conversation.DEFAULT_ID.equals(clean)) {
            ensureDefault();
        }
        return clean;
    }
}
