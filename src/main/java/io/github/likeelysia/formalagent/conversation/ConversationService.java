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
