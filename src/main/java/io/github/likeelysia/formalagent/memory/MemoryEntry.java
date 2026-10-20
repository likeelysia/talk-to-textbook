package io.github.likeelysia.formalagent.memory;

import java.util.List;
import java.util.UUID;

/**
 * 一条长期记忆:从某一段对话里提炼出来的、值得长期留着的一句话(事实 / 偏好 / 结论)。
 *
 * <p><b>和短期记忆的区别</b>:短期记忆回答的是"这个会话刚才聊到哪儿了",会话一关就没意义;
 * 长期记忆回答的是"关于这个人/这件事,我们已经知道什么",要跨会话带着走。
 *
 * <p><b>scopeId(记忆域)</b>:记忆按它分桶存 —— 默认每个会话自己一桶;
 * 把几个会话的 scopeId 指成同一个值,它们就共用一份长期记忆。
 *
 * <p><b>embedding(向量)</b>:存着它,召回时只给"当前提问"算一次向量,
 * 就能在当地把几十条记忆排个序(不用每条都发一次请求)。模型换了旧向量就作废,
 * 由 {@link MemoryStore} 在读取时清掉(宁可退化成按时间取,也不拿两套坐标比大小)。
 *
 * <p><b>pinned / origin(治理)</b>:
 * <ul>
 *   <li>{@code origin} = 这条记忆<b>从哪来的</b>:用户自己加的 / 明确要求记住的({@code user}),
 *       还是助手在后台提炼出来的({@code auto});</li>
 *   <li>{@code pinned} = <b>钉住</b>:记忆域满了要淘汰时,钉住的永远不动
 *       —— 他亲口让我记的事,不能因为条数满就悄悄丢掉;</li>
 *   <li>{@code lastAccessAt} = <b>最后一次被召回是什么时候</b>(淘汰时按"最久没用"先走)。</li>
 * </ul>
 *
 * <p><b>status / supersedes(对账)</b>:新记忆和旧记忆冲突时,不是直接改旧的那条,
 * 而是新建一条、把旧条标成 {@code superseded}(软覆盖,历史还在),并用 {@code supersedes}
 * 记下"我覆盖了哪些旧 id";用户明确说忘掉时标 {@code deleted}(软删)。
 * 召回和界面默认只认 {@code active}。
 *
 * <p><b>⚠️ 命名陷阱(踩过):</b>这里的方法叫 {@code hasEmbedding()} / {@code fromUser()},
 * 而不是 {@code isEmbedding()} / {@code isUserOrigin()}
 * —— Jackson 只把 {@code getXxx()} / {@code isXxx()} 形式的无参方法当成 JSON 属性,
 * 叫 {@code isXxx()} 会让写出去的 JSON 多一个字段、回读时报"未知字段"。
 */
public record MemoryEntry(
        String id,
        String scopeId,
        String text,
        String conversationId,
        long createdAt,
        long lastAccessAt,
        boolean pinned,
        String origin,
        float[] embedding,
        long updatedAt,
        String status,
        List<String> supersedes) {

    /** 单条记忆最多留几个字:模型不听话写小作文时兜一下,免得把上下文预算吃掉。 */
    public static final int TEXT_MAX = 200;

    /** 来源:用户自己加的 / 用户明确要求记住的(→ 入库即钉住)。 */
    public static final String ORIGIN_USER = "user";

    /** 来源:助手在后台从对话里提炼出来的。 */
    public static final String ORIGIN_AUTO = "auto";

    /** 正常可用。 */
    public static final String STATUS_ACTIVE = "active";

    /** 被新版本覆盖(软覆盖:历史还在,只是不再召回)。 */
    public static final String STATUS_SUPERSEDED = "superseded";

    /** 用户明确要求忘掉(软删:历史还在,只是不再召回)。 */
    public static final String STATUS_DELETED = "deleted";

    public MemoryEntry {
        text = normalize(text);
        origin = (origin == null || origin.isBlank()) ? ORIGIN_AUTO : origin;
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        // 老文件(加这个字段之前写的)没有 lastAccessAt → 拿创建时间顶上,别让它变成"1970 年用过"
        lastAccessAt = lastAccessAt <= 0 ? createdAt : lastAccessAt;
        updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
        status = (status == null || status.isBlank()) ? STATUS_ACTIVE : status;
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        embedding = (embedding == null || embedding.length == 0) ? null : embedding.clone();
    }

    /** 新造一条记忆(id 自动生成,时间取现在,来源 = 自动提炼)。 */
    public static MemoryEntry of(String scopeId, String text, String conversationId, float[] embedding) {
        return new MemoryEntry(newId(), scopeId, text, conversationId,
                System.currentTimeMillis(), 0L, false, ORIGIN_AUTO, embedding,
                0L, STATUS_ACTIVE, List.of());
    }

    /** 用户自己加 / 明确要求记住的一条:直接钉住,永不淘汰。 */
    public static MemoryEntry user(String scopeId, String text, String conversationId, float[] embedding) {
        return new MemoryEntry(newId(), scopeId, text, conversationId,
                System.currentTimeMillis(), 0L, true, ORIGIN_USER, embedding,
                0L, STATUS_ACTIVE, List.of());
    }

    /** 指定 id / 时间去造一条(测试用:写死时间才好断言"谁先谁后")。 */
    public static MemoryEntry at(String id, String scopeId, String text, String conversationId,
                                 long createdAt, float[] embedding) {
        return new MemoryEntry(id, scopeId, text, conversationId, createdAt, createdAt, false, ORIGIN_AUTO,
                embedding, createdAt, STATUS_ACTIVE, List.of());
    }

    /**
     * 造一条"覆盖旧记忆"的新记忆。
     *
     * <p>旧条不删,由调用方标 {@code superseded};新条记住 {@code supersedes = 旧条 (及旧条覆盖过的更早版本)}。
     * 钉住状态、来源跟着旧条走 —— 这是"同一件事",不是新冒出来的一件事。
     */
    public static MemoryEntry superseding(MemoryEntry old, String newText, String conversationId,
                                          float[] embedding) {
        List<String> replaced = new java.util.ArrayList<>(old.supersedes);
        replaced.add(old.id);
        return new MemoryEntry(newId(), old.scopeId, newText, conversationId,
                System.currentTimeMillis(), 0L, old.pinned, old.origin, embedding,
                0L, STATUS_ACTIVE, replaced);
    }

    /**
     * 内容更新(去重合并时用):同一件事又说了一遍,就换掉原文,不新增一条。
     *
     * <p>时间戳、钉住状态、来源都保持<b>原来那条</b>的 —— 它们属于"这件事",不属于这一次的说法。
     */
    public MemoryEntry withText(String newText, float[] newEmbedding) {
        return new MemoryEntry(id, scopeId, newText, conversationId, createdAt, lastAccessAt, pinned, origin,
                newEmbedding, System.currentTimeMillis(), status, supersedes);
    }

    /** 用户在界面上改了一条(改文字 / 钉住 / 取消钉住)。 */
    public MemoryEntry edited(String newText, boolean newPinned, float[] newEmbedding) {
        return new MemoryEntry(id, scopeId, newText, conversationId, createdAt, lastAccessAt, newPinned, origin,
                newEmbedding, System.currentTimeMillis(), STATUS_ACTIVE, supersedes);
    }

    /** 钉住 / 取消钉住。 */
    public MemoryEntry withPinned(boolean newPinned) {
        return new MemoryEntry(id, scopeId, text, conversationId, createdAt, lastAccessAt, newPinned, origin,
                embedding, System.currentTimeMillis(), status, supersedes);
    }

    /** 记一下"这条刚被召回过"(淘汰时按"最久没用"先走)。 */
    public MemoryEntry withAccess(long when) {
        return new MemoryEntry(id, scopeId, text, conversationId, createdAt, when, pinned, origin, embedding,
                updatedAt, status, supersedes);
    }

    /** 改状态(软覆盖 / 软删)。 */
    public MemoryEntry withStatus(String newStatus) {
        return new MemoryEntry(id, scopeId, text, conversationId, createdAt, lastAccessAt, pinned, origin,
                embedding, System.currentTimeMillis(), newStatus, supersedes);
    }

    /** 被新版本覆盖。 */
    public MemoryEntry superseded() {
        return withStatus(STATUS_SUPERSEDED);
    }

    /** 用户要求忘掉(软删)。 */
    public MemoryEntry deleted() {
        return withStatus(STATUS_DELETED);
    }

    /** 还能不能被召回 / 显示。 */
    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 有没有向量(没有 = 召回时只能按时间排,不能按相似度排)。 */
    public boolean hasEmbedding() {
        return embedding != null;
    }

    /** 是不是用户自己加 / 明确要求记住的(⚠️ 不叫 {@code isUserOrigin()},见类注释)。 */
    public boolean fromUser() {
        return ORIGIN_USER.equals(origin);
    }

    /** 读的时候给一份拷贝 —— 数组是可变对象,别让人顺着 accessor 改到里面的值。 */
    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    /** 取原文(空白直接当空串)。 */
    private static String normalize(String text) {
        String one = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return one.length() <= TEXT_MAX ? one : one.substring(0, TEXT_MAX) + "…";
    }

    private static String newId() {
        return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
