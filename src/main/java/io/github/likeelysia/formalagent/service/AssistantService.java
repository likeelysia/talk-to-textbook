package io.github.likeelysia.formalagent.service;

import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.agent.StreamSink;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.ShortTermMemory;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「一轮问答」的编排:短期记忆 → Agent → 落库。
 *
 * <p>把这几步收到一个地方,Controller 就只管 HTTP、{@link AgentService} 只管 ReAct 循环,
 * 中间“这个会话之前聊过什么 / 关于用户已知什么”由这里负责:
 * 短期记忆(最近几轮 + 滚动摘要)+ 长期记忆(召回 / 后台提炼)都插在这一层。
 *
 * <pre>
 *   1. 先把会话 id 验掉   → 不存在的 id 直接报错,不白烧 token
 *   2. 算出历史           → 长期记忆(召回)+ 最近几轮原文 + 滚动摘要(超窗口的顺手压进摘要)
 *   3. 跑 Agent           → 模型看到的是"系统提示 + 历史 + 这次的提问"
 *   4. 记进会话           → 下一轮它就能看见这一轮了
 * </pre>
 */
@Component
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final AgentService agent;
    private final ConversationService conversations;
    private final ShortTermMemory shortTerm;
    private final LongTermMemory longTerm;

    public AssistantService(AgentService agent, ConversationService conversations,
                            ShortTermMemory shortTerm, LongTermMemory longTerm) {
        this.agent = agent;
        this.conversations = conversations;
        this.shortTerm = shortTerm;
        this.longTerm = longTerm;
    }

    /** 一轮问答的结果:答案 + 这一轮记到了哪个会话。 */
    public record Turn(AgentResult result, String conversationId) {
    }

    /**
     * 问一轮。
     *
     * @param conversationId 会话 id(可空 = 默认对话)
     * @param modelQuestion  真正发给模型的问题(图片链路上含识别出来的内容)
     * @param displayQuestion 存进历史的那句话(图片链路上是"用户原话 / 几张图")
     */
    public Turn ask(String conversationId, String modelQuestion, String displayQuestion) {
        return ask(conversationId, modelQuestion, displayQuestion, null);
    }

    /**
     * 流式版:和 {@link #ask} 走完全同一套流程(验会话 → 拼历史/摘要 → 跑 Agent → 落库 → 后台提炼),
     * 只是答案边生成边经过 {@code sink} 推给调用方。
     *
     * <p><b>落库和记忆提炼一步不少</b>:它们都在 Agent 跑完之后,与流式无关 ——
     * 不能因为"答案已经边吐边显示了"就漏掉写历史。
     */
    public Turn askStream(String conversationId, String modelQuestion, String displayQuestion, StreamSink sink) {
        return ask(conversationId, modelQuestion, displayQuestion, sink);
    }

    /** 一轮问答的真正实现;sink 为 null = 非流式。 */
    private Turn ask(String conversationId, String modelQuestion, String displayQuestion, StreamSink sink) {
        String id = conversations.requireExisting(conversationId);
        Conversation conversation = conversations.get(id);

        // 长期记忆:拿“这次的提问”去记忆域里召回(跨会话累积的偏好 / 事实)
        List<AgentMessage> history = new ArrayList<>();
        AgentMessage recalled = longTerm.recallAsMessage(conversation.scopeId(), modelQuestion);
        if (recalled != null) {
            history.add(recalled);
        }

        // 先把窗口外那几条旧对话并进摘要,再拼上下文。注意是“先压、后用更新过的会话重拼一次” ——
        // 先生成 ctx 再改摘要的话,这一轮模型看到的还是旧 summary,要等到下一轮才生效。
        ShortTermMemory.Context context = shortTerm.build(conversation);
        if (shortTerm.summarizing() && !context.evicted().isEmpty()) {
            int evicted = context.evicted().size();
            String merged = shortTerm.summarize(conversation.summary(), context.evicted());
            Conversation updated = conversations.applySummary(id, merged, context.coversUpTo());
            context = shortTerm.build(updated);      // 拿更新过的会话重拼一次,这一轮就带上新摘要
            log.info("会话 {} 的滚动摘要已更新(并进 {} 条旧消息)", id, evicted);
        }
        history.addAll(context.messages());

        AgentResult result = (sink == null)
                ? agent.run(modelQuestion, history)
                : agent.runStream(modelQuestion, history, sink);
        Conversation recorded = conversations.record(id, displayQuestion, result.answer(), result.trace());
        longTerm.maybeExtract(recorded);          // 攒够几轮就在后台提炼一次(不占用户等答案的时间)
        return new Turn(result, id);
    }
}
