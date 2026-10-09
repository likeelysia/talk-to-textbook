package io.github.likeelysia.formalagent.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个对话框(会话)。
 *
 * <p><b>为什么是 record(不可变)而不是普通可变类:</b>
 * <ol>
 *   <li>会话会被多个线程碰(Web 请求 + 后台提取),可变对象要处处加锁才安全;
 *       不可变对象"改"是<b>造一个新的</b>,天然没有"读到一半被改"的问题;</li>
 *   <li>落盘就是"把当前这个值写出去",不会出现"写的时候还在被人改"的半截数据;</li>
 *   <li>Jackson 对 record 支持良好(主分支 2.12+ 原生支持),JSON 读写都不用手写映射。</li>
 * </ol>
 *
 * <p><b>scopeId(记忆域)</b>:长期记忆按它隔离 —— 默认 {@code scopeId == id},
 * 也就是"每个会话自己一份记忆"。以后要让若干会话共享记忆,就是把它们的 scopeId 改成同一个值。
 * (本批次只落字段、不实现共享逻辑。)
 *
 * <p><b>summarizedCount(摘要水位)</b>:短期记忆用的。会话聊长了以后,把最老的一段压成
 * {@code summary} 一句话,再把“已经压掉了几条消息”记在这里 —— 下次拼上下文就从这条之后开始取,
 * 已经进过摘要的老消息不会重复喂给模型。
 */
public record Conversation(
        String id,
        String title,
        String scopeId,
        long createdAt,
        long updatedAt,
        String summary,
        int summarizedCount,
        List<ChatMessage> messages) {

    /** 默认会话的固定 id:前端不传 id 时都落到它头上(所以第一次打开就有得聊)。 */
    public static final String DEFAULT_ID = "default";

    /** 默认会话的标题。 */
    public static final String DEFAULT_TITLE = "默认对话";

    /** 新建会话时的占位标题:"首条提问"会自动把它换掉。 */
    public static final String NEW_TITLE = "新对话";

    /** 自动取标题时最多留几个字。 */
    private static final int TITLE_MAX = 20;

    public Conversation {
        title = (title == null || title.isBlank()) ? NEW_TITLE : title.strip();
        scopeId = (scopeId == null || scopeId.isBlank()) ? id : scopeId;
        messages = messages == null ? List.of() : List.copyOf(messages);
        summarizedCount = Math.min(Math.max(0, summarizedCount), messages.size());
    }

    /** 造一个新会话:创建时间 = 更新时间 = 现在,记忆域默认独占。 */
    public static Conversation of(String id, String title) {
        long now = System.currentTimeMillis();
        return new Conversation(id, title, id, now, now, null, 0, List.of());
    }

    /** 追加一条消息(返回新对象,原对象不动)。 */
    public Conversation appended(ChatMessage message) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        return new Conversation(id, title, scopeId, createdAt, System.currentTimeMillis(),
                summary, summarizedCount, next);
    }

    /** 改名。 */
    public Conversation renamed(String newTitle) {
        return new Conversation(id, newTitle, scopeId, createdAt, System.currentTimeMillis(),
                summary, summarizedCount, messages);
    }

    /** 换记忆域(共享 / 拆回独立都走它)。 */
    public Conversation withScope(String newScopeId) {
        return new Conversation(id, title, newScopeId, createdAt, System.currentTimeMillis(),
                summary, summarizedCount, messages);
    }

    /**
     * 记上本会话的滚动摘要(短期记忆压缩出来的东西)。
     *
     * @param covered 这份摘要已经“吃进去”的前多少条消息 —— 下次拼上下文时,这段不再重复喂给模型。
     *                <b>只有摘要真写成功了才能推进它</b>,否则那几条历史就凭空消失了。
     */
    public Conversation withSummary(String newSummary, int covered) {
        return new Conversation(id, title, scopeId, createdAt, System.currentTimeMillis(),
                newSummary, covered, messages);
    }

    /**
     * 标题还是占位符时,拿第一条用户消息当标题。
     * 好处:用户什么都不用做,侧栏里就能认出"这个框聊的是啥"。
     */
    public Conversation autoTitled() {
        boolean placeholder = DEFAULT_TITLE.equals(title) || NEW_TITLE.equals(title);
        if (!placeholder) {
            return this;
        }
        String first = messages.stream()
                .filter(ChatMessage::fromUser)
                .map(ChatMessage::content)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse(null);
        return first == null ? this : renamed(shorten(first));
    }

    public int messageCount() {
        return messages.size();
    }

    /** 列表页要用的"轻量版"(不含正文,只给标题栏用)。 */
    public ConversationMeta meta() {
        return new ConversationMeta(id, title, scopeId, createdAt, updatedAt, messages.size());
    }

    private static String shorten(String text) {
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= TITLE_MAX ? one : one.substring(0, TITLE_MAX) + "…";
    }
}
