package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.Vectors;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 长期记忆:跨轮累积下来的「关于这个人 / 这件事,我们已经知道什么」。
 *
 * <pre>
 *   聊到第 6 轮 → 后台提炼一次(规则预筛 + 模型打分)→ 写进记忆域
 *   下一次提问 → 拿提问去记忆域里召回最相关的几条 → 作为 system 消息带上
 * </pre>
 *
 * <p><b>为什么要有它(短期记忆不够吗)</b>:短期记忆是"窗口 + 摘要",窗口一滑、
 * 摘要一压,细节就没了;而且它只在<b>一个会话里</b>有效。长期记忆回答的是另一个问题:
 * "不管过了多少轮、换了多少个会话,关于用户已经确定的那些事"。
 *
 * <p><b>三个刻意的设计</b>:
 * <ol>
 *   <li><b>提炼在后台跑</b>:它是"顺手记笔记",不该让用户多等一秒
 *       (摘要必须同步 —— 这一轮就得用;提炼不是);</li>
 *   <li><b>失败不拖垮问答</b>:模型挂了、embedding 没开,顶多这轮记不上,
 *       水位照推(不然后面每一轮都对着同一段重试);</li>
 *   <li><b>embedding 不可用就降级</b>:召回从"按相似度取 top-k"退化成"按时间取最近 k 条"
 *       —— 宁可记得少一点,也不要因为记笔记把聊天搞挂。</li>
 * </ol>
 */
@Component
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    private static final int DEFAULT_EXTRACT_EVERY_TURNS = 6;
    private static final int DEFAULT_RECALL_TOP_K = 5;
    private static final double DEFAULT_DEDUP_SIMILARITY = 0.9;
    private static final int DEFAULT_MAX_STORED = 500;

    /** 召回时加的一句"说明书":让模型知道这些不是用户刚说的话。 */
    static final String HEAD =
            "[长期记忆 —— 关于这位用户 / 这段对话,之前已经确认过的事。供你参考,不必复述,也不要当成用户的新问题。\n"
                    + "这里只是历史摘录,可能不全:这里没有的信息,照实说“不记得”就行,不要编造用户说过的话]\n";

    private final MemoryStore store;
    private final MemoryExtractor extractor;
    private final EmbeddingClient embedder;
    private final ConversationService conversations;
    private final AppConfig.Memory.LongTerm config;
    private final Executor executor;

    @Autowired
    public LongTermMemory(MemoryStore store, MemoryExtractor extractor, EmbeddingClient embedder,
                          ConversationService conversations, AppConfig config) {
        this(store, extractor, embedder, conversations, longTermOf(config), worker());
    }

    /** 测试 / 手工装配用:传一个"就在当前线程跑"的 Executor,提炼就变成同步的,断言才好写。 */
    public LongTermMemory(MemoryStore store, MemoryExtractor extractor, EmbeddingClient embedder,
                          ConversationService conversations, AppConfig.Memory.LongTerm config,
                          Executor executor) {
        this.store = store;
        this.extractor = extractor;
        this.embedder = embedder;
        this.conversations = conversations;
        this.config = config;
        this.executor = executor;
    }

    /** 开关(关掉 = 既不提炼也不召回)。 */
    public boolean enabled() {
        return config == null || config.enabled();
    }

    // ==================== 召回 ====================

    /**
     * 拿「这次的提问」去召回长期记忆,拼成一条 system 消息。
     *
     * @return 没有可用的记忆时返回 {@code null}(调用方直接跳过这条)
     */
    public AgentMessage recallAsMessage(String scopeId, String query) {
        List<MemoryEntry> hits = recall(scopeId, query);
        if (hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(HEAD);
        for (MemoryEntry entry : hits) {
            sb.append("- ").append(entry.text()).append('\n');
        }
        return AgentMessage.system(sb.toString().strip());
    }

    public List<MemoryEntry> recall(String scopeId, String query) {
        return recall(scopeId, query, recallTopK());
    }

    /**
     * 召回最相关的 k 条。
     *
     * <p>故意<b>不设相似度阈值</b>:一个记忆域里的条目本来就同主题(默认 = 同一个会话),
     * 而且条数不多 —— 与其拿一个拍脑袋的阈值把好记忆筛掉,不如让"最相关的几条"优先排前面。
     */
    public List<MemoryEntry> recall(String scopeId, String query, int k) {
        if (!enabled() || scopeId == null || k <= 0) {
            return List.of();
        }
        List<MemoryEntry> entries = store.list(scopeId);
        if (entries.isEmpty()) {
            return List.of();
        }

        float[] queryVector = tryEmbed(query);
        List<MemoryEntry> hits = queryVector == null
                // embedding 没开:退化成"字面命中优先,同分再比时间"
                ? entries.stream()
                        .sorted(Comparator.comparingDouble((MemoryEntry e) -> keywordScore(query, e.text())).reversed()
                                .thenComparing(Comparator.comparingLong(MemoryEntry::createdAt).reversed()))
                        .limit(k)
                        .toList()
                : entries.stream()
                        .sorted(Comparator.comparingDouble((MemoryEntry e) -> similarity(queryVector, e)).reversed()
                                .thenComparing(Comparator.comparingLong(MemoryEntry::createdAt).reversed()))
                        .limit(k)
                        .toList();
        store.touch(scopeId, hits.stream().map(MemoryEntry::id).toList());   // 记下"这几条刚被用过"(淘汰排序用)
        return hits;
    }

    private static double similarity(float[] queryVector, MemoryEntry entry) {
        float[] vector = entry.embedding();
        return vector == null ? -1 : Vectors.cosine(queryVector, vector);   // 没向量的排在最后
    }

    /**
     * 没有向量时的「字面命中分」:提问里有多少内容在这条记忆里出现过(0 ~ 1)。
     *
     * <p><b>为什么要有这一步</b>:纯按时间召回 = "最近记的几条",跟提问毫无关系 ——
     * 早些时候说的重要事情,会被后来记的挤出去,从此永远召不回来。
     * 加上这一步,至少"提问里出现过的词"能把对应的记忆顶到前面。
     *
     * <p><b>为什么不用分词器</b>:中文没空格,直接拿<b>相邻两字(2-gram)</b>当词用 ——
     * 免依赖、够用,"准备"这种词天然就被切出来了。
     *
     * <p><b>只增不改</b>:一条都没命中时,所有分数并列 0 → 自动退回去按时间排序,
     * 行为和以前完全一样。
     */
    static double keywordScore(String query, String text) {
        Set<String> asked = grams(normalize(query));
        if (asked.isEmpty()) {
            return 0;
        }
        Set<String> known = grams(normalize(text));
        if (known.isEmpty()) {
            return 0;
        }
        long hit = asked.stream().filter(known::contains).count();
        return (double) hit / asked.size();
    }

    /** 只留字母数字与汉字、统一小写 —— 标点空格不参与匹配。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 相邻两字的切片集合(中文的"土分词")。 */
    private static Set<String> grams(String text) {
        if (text.isEmpty()) {
            return Set.of();
        }
        if (text.length() < 2) {
            return Set.of(text);
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            out.add(text.substring(i, i + 2));
        }
        return out;
    }

    // ==================== 提炼 ====================

    /** 攒够 N 轮就提炼一次(后台跑,不阻塞用户等答案)。 */
    public void maybeExtract(Conversation conversation) {
        if (!enabled() || conversation == null) {
            return;
        }
        List<ChatMessage> pending = pendingMessages(conversation);
        if (pending.isEmpty()) {
            return;
        }
        executor.execute(() -> extract(conversation, pending));
    }

    /**
     * 提炼一次并落盘(同步执行;后台通道与测试都走它)。
     *
     * @param conversation 提炼时那一刻的会话快照(不可变,不用担心被人改)
     * @param pending      这一段还没提炼过的消息
     */
    void extract(Conversation conversation, List<ChatMessage> pending) {
        // ⚠️ 水位推到"快照里有几条消息",而不是"现在有几条" ——
        // 提炼跑着的时候可能又来了新消息,推过头那几轮就永远提炼不着了。
        int watermark = conversation.messages().size();
        try {
            List<MemoryCandidate> candidates = extractor.extract(pending);
            if (!candidates.isEmpty()) {
                long[] counts = storeMemories(conversation.scopeId(), candidates, conversation.id());
                log.info("会话 {} 提炼出 {} 条长期记忆(新增 {} / 合并 {}),记忆域 {}",
                        conversation.id(), candidates.size(), counts[0], counts[1], conversation.scopeId());
            }
        } catch (RuntimeException e) {
            log.warn("长期记忆提炼失败(不影响问答):{}", e.getMessage());
        } finally {
            conversations.markMemorized(conversation.id(), watermark);
        }
    }

    /** 该不该提炼了:还没提炼过的那一段,够不够 N 轮。 */
    private List<ChatMessage> pendingMessages(Conversation conversation) {
        int from = Math.min(Math.max(conversation.memoryCount(), 0), conversation.messages().size());
        List<ChatMessage> tail = conversation.messages().subList(from, conversation.messages().size());
        long turns = tail.stream().filter(ChatMessage::fromUser).count();
        return turns >= extractEveryTurns() ? List.copyOf(tail) : List.of();
    }

    /**
     * 落盘:先给候选算向量,再"和已有的比一比" —— 像到一定程度就当成同一件事(合并),
     * 否则新增。
     *
     * @return {新增条数, 合并条数}
     */
    long[] storeMemories(String scopeId, List<MemoryCandidate> candidates, String conversationId) {
        float[][] vectors = tryEmbedAll(candidates.stream().map(MemoryCandidate::text).toList());
        long[] counts = new long[2];
        store.update(scopeId, entries -> {
            List<MemoryEntry> merged = new ArrayList<>(entries);
            for (int i = 0; i < candidates.size(); i++) {
                MemoryCandidate candidate = candidates.get(i);
                float[] vector = vectors[i];
                int duplicate = indexOfDuplicate(merged, vector);
                if (duplicate >= 0) {
                    // 合并:原文换成新的;用户明确要求记住的顺手钉住(又说了一遍 = 再确认一次)
                    MemoryEntry old = merged.get(duplicate);
                    merged.set(duplicate, old.withText(candidate.text(), vector)
                            .withPinned(old.pinned() || candidate.mustRemember()));
                    counts[1]++;
                } else {
                    merged.add(candidate.mustRemember()
                            ? MemoryEntry.user(scopeId, candidate.text(), conversationId, vector)
                            : MemoryEntry.of(scopeId, candidate.text(), conversationId, vector));
                    counts[0]++;
                }
            }
            return trim(scopeId, merged);          // 超上限就淘汰"最久没用"的(钉住的不动)
        });
        return counts;
    }

    private int indexOfDuplicate(List<MemoryEntry> entries, float[] vector) {
        if (vector == null) {
            return -1;                       // 没向量就没法比 → 宁可重复一条,也不要误合并
        }
        double threshold = dedupSimilarity();
        for (int i = 0; i < entries.size(); i++) {
            float[] existing = entries.get(i).embedding();
            if (existing != null && Vectors.cosine(vector, existing) > threshold) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 治理:用户自己看 / 改 / 删 ====================

    /** 列出某个会话的记忆域里现有的全部记忆(新的在前)。 */
    public List<MemoryEntry> list(String conversationId) {
        List<MemoryEntry> entries = new ArrayList<>(store.list(scopeOf(conversationId)));
        entries.sort(Comparator.comparingLong((MemoryEntry e) -> e.createdAt()).reversed());
        return List.copyOf(entries);
    }

    /**
     * 用户手动加一条 —— 他说了算,直接<b>钉住</b>(永不淘汰)。
     *
     * <p>顺手做一次向量去重:跟已有条目太像的就合并(换成新的原文),不重复堆一条。
     */
    public MemoryEntry remember(String conversationId, String text) {
        String scope = scopeOf(conversationId);
        String one = text == null ? "" : text.strip();
        if (one.isBlank()) {
            throw new AgentException("记忆内容不能是空的");
        }
        float[] vector = tryEmbed(one);
        MemoryEntry[] saved = new MemoryEntry[1];     // update 里锁着读改写,结果只能这样捐出来
        store.update(scope, entries -> {
            List<MemoryEntry> next = new ArrayList<>(entries);
            int duplicate = indexOfDuplicate(next, vector);
            if (duplicate >= 0) {
                saved[0] = next.get(duplicate).withText(one, vector).withPinned(true);
                next.set(duplicate, saved[0]);
            } else {
                saved[0] = MemoryEntry.user(scope, one, conversationId, vector);
                next.add(saved[0]);
            }
            return trim(scope, next);
        });
        return saved[0];
    }

    /**
     * 改一条(改文字 / 钉住 / 取消钉住)。{@code text} 空 = 不改文字;{@code pinned} 为 null = 不改钉住状态。
     *
     * <p>换过文字就顺手重算向量(不然它还是拿旧意思去跟别的记忆比相似度)。
     */
    public MemoryEntry edit(String conversationId, String id, String text, Boolean pinned) {
        String scope = scopeOf(conversationId);
        boolean hasText = text != null && !text.isBlank();
        float[] vector = hasText ? tryEmbed(text.strip()) : null;
        MemoryEntry[] saved = new MemoryEntry[1];
        store.update(scope, entries -> {
            List<MemoryEntry> next = new ArrayList<>(entries.size());
            for (MemoryEntry entry : entries) {
                if (!entry.id().equals(id)) {
                    next.add(entry);
                    continue;
                }
                String finalText = hasText ? text.strip() : entry.text();
                float[] finalVector = hasText ? vector : entry.embedding();
                boolean finalPinned = pinned == null ? entry.pinned() : pinned;
                saved[0] = entry.edited(finalText, finalPinned, finalVector);
                next.add(saved[0]);
            }
            if (saved[0] == null) {
                throw new AgentException("没有这条记忆:" + id);
            }
            return trim(scope, next);
        });
        return saved[0];
    }

    /** 删一条。@return 真删掉了才是 true */
    public boolean forget(String conversationId, String id) {
        String scope = scopeOf(conversationId);
        boolean[] removed = new boolean[1];
        store.update(scope, entries -> entries.stream()
                .filter(entry -> {
                    boolean hit = entry.id().equals(id);
                    if (hit) {
                        removed[0] = true;
                    }
                    return !hit;
                })
                .toList());
        return removed[0];
    }

    /**
     * 忘掉一个记忆域的全部记忆。
     *
     * <p>⚠️ 会话里的 {@code memoryCount} 水位<b>不跟着回退</b>:忘掉就是忘掉,
     * 已经聊过的那些轮不会被重新提炼一遍(不然用户刚删的东西,下一轮又自己长回来)。
     */
    public void forgetAll(String conversationId) {
        store.delete(scopeOf(conversationId));
    }

    /** 会话 id → 记忆域(以后要共享记忆,就是把几个会话指到同一个 scopeId,这里不用动)。 */
    private String scopeOf(String conversationId) {
        String id = conversations.requireExisting(conversationId);
        return conversations.get(id).scopeId();
    }

    /**
     * 记忆域的总量上限:超了就淘汰"最久没被用到"的。
     *
     * <p>淘汰顺序:<b>钉住的排在最后</b>(永不淘汰)→ 其它按"最后一次被召回"从久到近 → 同分再比谁记得更早。
     * 这样"常在用的事实"活得久,"记了就没再用过的"先走。
     */
    List<MemoryEntry> trim(String scopeId, List<MemoryEntry> entries) {
        int max = maxStored();
        if (max <= 0 || entries.size() <= max) {
            return entries;
        }
        List<MemoryEntry> byPriority = new ArrayList<>(entries);
        byPriority.sort(Comparator.comparing((MemoryEntry e) -> e.pinned())
                .thenComparingLong((MemoryEntry e) -> e.lastAccessAt())
                .thenComparingLong((MemoryEntry e) -> e.createdAt()));
        List<MemoryEntry> kept = new ArrayList<>(byPriority.subList(byPriority.size() - max, byPriority.size()));
        kept.sort(Comparator.comparingLong((MemoryEntry e) -> e.createdAt()));
        log.info("记忆域 {} 超过上限({} > {}),淘汰了最久没被用的 {} 条", scopeId, entries.size(), max,
                entries.size() - max);
        return kept;
    }

    // ==================== 小工具 ====================

    private float[] tryEmbed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return embedder.embed(text);
        } catch (RuntimeException e) {
            log.warn("embedding 服务不可用,长期记忆退化成按时间召回:{}", e.getMessage());
            return null;
        }
    }

    private float[][] tryEmbedAll(List<String> texts) {
        float[][] vectors = new float[texts.size()][];
        try {
            List<float[]> computed = embedder.embedAll(texts);
            for (int i = 0; i < Math.min(computed.size(), vectors.length); i++) {
                vectors[i] = computed.get(i);
            }
        } catch (RuntimeException e) {
            log.warn("embedding 服务不可用,这批记忆先不建向量(召回会退化成按时间取):{}", e.getMessage());
        }
        return vectors;
    }

    private int extractEveryTurns() {
        int configured = config == null ? 0 : config.extractEveryTurns();
        return configured > 0 ? configured : DEFAULT_EXTRACT_EVERY_TURNS;
    }

    private int recallTopK() {
        int configured = config == null ? 0 : config.recallTopK();
        return configured > 0 ? configured : DEFAULT_RECALL_TOP_K;
    }

    private double dedupSimilarity() {
        double configured = config == null ? 0 : config.dedupSimilarity();
        return configured > 0 ? configured : DEFAULT_DEDUP_SIMILARITY;
    }

    private int maxStored() {
        int configured = config == null ? 0 : config.maxStored();
        return configured > 0 ? configured : DEFAULT_MAX_STORED;
    }

    /** 单线程 + 守护线程:顺序提炼(不抢资源),进程退出不拦着。 */
    private static Executor worker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "long-term-memory");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static AppConfig.Memory.LongTerm longTermOf(AppConfig config) {
        return config == null || config.memory() == null ? null : config.memory().longTerm();
    }
}
