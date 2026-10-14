package io.github.likeelysia.formalagent.conversation;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.Vectors;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 「存笔记」的业务逻辑:把用户勾选的问题,连同「AI 对这一问的相关回答」一起收进笔记本。
 *
 * <p><b>怎么找"相关回答"</b>(用户要的「乙」):把这条问题的向量,和这个会话里**所有 AI 回答**的向量
 * 算余弦 —— 同一轮那条**必收**,其余相似度 ≥ {@link #RELATED_THRESHOLD} 的**也收**
 * (比如后面追问里补充的解释)。这样"一个问题"能把散落会话各处的相关回答都归拢过来。
 *
 * <p><b>embedding 不可用就降级</b>:退回"只收同一轮那条"(甲),绝不让"存笔记"这个动作失败。
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    /**
     * 语义相关的门槛(0~1 余弦)。
     * <p>实测标定(bge-m3 尺度):相关 ≈ 0.6+,不相关 ≈ 0.3 上下 —— 0.55 能把"明显相关"挑出来,
     * 又不至于把无关的拉进来。
     */
    static final double RELATED_THRESHOLD = 0.55;

    /** 一条笔记最多收几条回答(免得追问几十轮全收进来)。 */
    private static final int MAX_ANSWERS = 8;

    private final ConversationService conversations;
    private final NoteStore store;
    private final EmbeddingClient embedder;

    @Autowired
    public NoteService(ConversationService conversations, NoteStore store,
                       @Nullable EmbeddingClient embedder) {
        this.conversations = conversations;
        this.store = store;
        this.embedder = embedder;
    }

    /** 存笔记的结果:真存了哪些 + 因为"已经有了"跳过了几条。 */
    public record SaveResult(List<Note> saved, int skipped) {
    }

    /**
     * 把某些会话里的某些「提问」存进笔记本。
     *
     * @param conversationId 哪个会话
     * @param indexes        勾选的问题在该会话 {@code messages} 里的下标(只认 role=user 的)
     */
    public SaveResult save(String conversationId, List<Integer> indexes) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new AgentException("conversationId 不能为空");
        }
        if (indexes == null || indexes.isEmpty()) {
            throw new AgentException("没有勾选任何问题");
        }
        Conversation conversation = conversations.get(conversationId);
        List<ChatMessage> messages = conversation.messages();

        // "已经有了"的笔记(按 会话+问题 去重),先把重复的挑出去,省得白算向量
        Set<String> existing = new LinkedHashSet<>();
        for (Note n : store.list()) {
            existing.add(n.dedupKey());
        }

        // AI 回答的向量(整个会话一次性算好,多条问题共用);算不了就是 null → 退回「甲」
        List<Integer> assistantIndexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).fromAssistant()) {
                assistantIndexes.add(i);
            }
        }
        List<float[]> assistantVectors = embedAll(assistantIndexes.stream()
                .map(i -> text(messages.get(i))).toList());

        long now = System.currentTimeMillis();
        List<Note> candidates = new ArrayList<>();
        int duplicate = 0;
        for (int index : new LinkedHashSet<>(indexes)) {          // 同一题被勾两次,只算一次
            if (index < 0 || index >= messages.size()) {
                continue;
            }
            ChatMessage message = messages.get(index);
            if (!message.fromUser()) {
                continue;                                        // 只认"用户的问题"
            }
            String question = text(message);
            if (question.isEmpty()) {
                continue;
            }
            Note note = new Note(newId(), conversationId, question, message.createdAt(),
                    pickAnswers(question, messages, index, assistantIndexes, assistantVectors), now);
            if (!existing.add(note.dedupKey())) {
                duplicate++;
                continue;
            }
            candidates.add(note);
        }

        List<Note> saved = store.addAll(candidates);
        return new SaveResult(saved, duplicate + (candidates.size() - saved.size()));
    }

    public List<Note> list() {
        return store.list();
    }

    public Note get(String id) {
        return store.find(id).orElseThrow(() -> new AgentException("笔记不存在:" + id));
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    // ==================== 挑"相关回答" ====================

    private List<Note.Answer> pickAnswers(String question, List<ChatMessage> messages, int questionIndex,
                                          List<Integer> assistantIndexes, @Nullable List<float[]> vectors) {
        Set<Integer> keep = new LinkedHashSet<>();

        // 甲:同一轮的回答 —— 问题之后、下一条用户提问之前的所有 AI 回答
        for (int i = questionIndex + 1; i < messages.size(); i++) {
            if (messages.get(i).fromUser()) {
                break;
            }
            if (messages.get(i).fromAssistant()) {
                keep.add(i);
            }
        }

        // 乙:整个会话里"和这问题语义相关"的其它回答
        if (vectors != null) {
            Optional<float[]> qv = embedOne(question);
            if (qv.isPresent()) {
                for (int k = 0; k < assistantIndexes.size(); k++) {
                    float[] v = vectors.get(k);
                    if (v != null && Vectors.cosine(qv.get(), v) >= RELATED_THRESHOLD) {
                        keep.add(assistantIndexes.get(k));
                    }
                }
            }
        }

        List<Integer> ordered = new ArrayList<>(keep);
        ordered.sort(Integer::compareTo);
        List<Note.Answer> answers = new ArrayList<>();
        for (int i : ordered) {
            if (answers.size() >= MAX_ANSWERS) {
                break;
            }
            ChatMessage m = messages.get(i);
            answers.add(new Note.Answer(text(m), m.createdAt(), m.trace()));
        }
        return answers;
    }

    private List<float[]> embedAll(List<String> texts) {
        if (embedder == null || texts.isEmpty()) {
            return null;
        }
        try {
            List<float[]> vectors = embedder.embedAll(texts);
            return vectors.size() == texts.size() ? vectors : null;
        } catch (RuntimeException e) {
            log.warn("embedding 不可用,这次的笔记只收「同一轮」的回答:{}", e.getMessage());
            return null;
        }
    }

    private Optional<float[]> embedOne(String text) {
        if (embedder == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(embedder.embed(text));
        } catch (RuntimeException e) {
            log.warn("embedding 不可用,跳过语义相关回答:{}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String text(ChatMessage message) {
        return message.content() == null ? "" : message.content().strip();
    }

    private static String newId() {
        return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 只给测试看:确认"甲的窗口"和"乙的门槛"用的是同一套参数。 */
    static Map<String, Object> tuning() {
        return Map.of("relatedThreshold", RELATED_THRESHOLD, "maxAnswers", MAX_ANSWERS);
    }
}
