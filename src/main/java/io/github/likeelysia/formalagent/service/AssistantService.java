package io.github.likeelysia.formalagent.service;

import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.agent.AgentService;
import io.github.likeelysia.formalagent.agent.StreamSink;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.memory.CoreMemoryStore;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.ShortTermMemory;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 统一上下文预算(字符,粗略≈token)。第一版用保守估算:
     * 中文 1 字≈1~2 token,这里按"1 字 = 1 token"算,宁可早一点截。
     */
    @Value("${fa.context.max-chars:14000}")
    private int maxContextChars = 14000;

    /** 给最终回答预留的空间;预算里先扣掉它。 */
    @Value("${fa.context.reserve-chars:3000}")
    private int reserveChars = 3000;

    private final AgentService agent;
    private final ConversationService conversations;
    private final ShortTermMemory shortTerm;
    private final LongTermMemory longTerm;
    private final CoreMemoryStore coreMemory;

    @Autowired
    public AssistantService(AgentService agent, ConversationService conversations,
                            ShortTermMemory shortTerm, LongTermMemory longTerm,
                            CoreMemoryStore coreMemory) {
        this.agent = agent;
        this.conversations = conversations;
        this.shortTerm = shortTerm;
        this.longTerm = longTerm;
        this.coreMemory = coreMemory;
    }

    /** 测试 / 手工装配用:不带核心档案。 */
    public AssistantService(AgentService agent, ConversationService conversations,
                            ShortTermMemory shortTerm, LongTermMemory longTerm) {
        this(agent, conversations, shortTerm, longTerm, null);
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

        // 核心档案:每轮都带的小抄(我是谁 / 目标 / 当前项目 / 偏好),排在长期记忆前面
        AgentMessage core = coreMemory == null ? null : coreMemory.asMessage();

        // 长期记忆:统一走 memoryScopeOf 解析到根域 —— 问答与管理接口不能一个读中间指针、一个读根
        String memoryScope = conversations.memoryScopeOf(id);
        AgentMessage recalled = longTerm.recallAsMessage(memoryScope, modelQuestion);

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

        // 所有层放进同一个预算:核心档案 > 长期记忆 > 摘要 > 最近几轮;超了先丢低相关/最旧的
        List<AgentMessage> history = assembleWithinBudget(core, recalled, context.messages(),
                modelQuestion == null ? 0 : modelQuestion.length());

        AgentResult result = (sink == null)
                ? agent.run(modelQuestion, history)
                : agent.runStream(modelQuestion, history, sink);
        Conversation recorded = conversations.record(id, displayQuestion, result.answer(), result.trace());
        longTerm.maybeExtract(recorded);          // 攒够几轮就在后台提炼一次(不占用户等答案的时间)
        return new Turn(result, id);
    }

    /**
     * 把所有层塞进同一个预算:核心档案 → 长期记忆 → 摘要 → 最近几轮。
     *
     * <p>顺序就是优先级:预算不够时先不注入长期记忆,再丢摘要,最后从最旧的轮次开始丢;
     * 当前提问单独占预算、永远保留。第一版按字符估算(偏保守),不是精确 token 计数。
     */
    private List<AgentMessage> assembleWithinBudget(AgentMessage core, AgentMessage recalled,
                                                    List<AgentMessage> contextMessages, int questionChars) {
        int limit = Math.max(800, maxContextChars - reserveChars - Math.max(0, questionChars));
        List<AgentMessage> out = new ArrayList<>();
        int used = 0;

        if (core != null) {
            out.add(core);
            used += length(core);
        }
        if (recalled != null) {
            if (used + length(recalled) <= limit) {
                out.add(recalled);
                used += length(recalled);
            } else {
                log.info("上下文预算不足,本轮不注入长期记忆");
            }
        }

        int start = 0;
        if (!contextMessages.isEmpty() && "system".equals(contextMessages.get(0).role())) {
            AgentMessage summary = contextMessages.get(0);
            if (used + length(summary) <= limit) {
                out.add(summary);
                used += length(summary);
            } else {
                log.info("上下文预算不足,本轮不注入摘要");
            }
            start = 1;
        }

        List<List<AgentMessage>> turns = splitTurns(contextMessages.subList(start, contextMessages.size()));
        List<List<AgentMessage>> kept = new ArrayList<>();
        int remaining = Math.max(0, limit - used);
        for (int i = turns.size() - 1; i >= 0; i--) {
            int turnLength = turns.get(i).stream().mapToInt(AssistantService::length).sum();
            if (turnLength <= remaining) {
                kept.add(0, turns.get(i));
                remaining -= turnLength;
            } else {
                break;
            }
        }
        if (kept.isEmpty() && !turns.isEmpty()) {
            // 预算再紧,也至少留最近一轮 —— 否则模型不知道"刚才在说什么"
            kept.add(turns.get(turns.size() - 1));
            log.warn("上下文预算不足,只保留了最近一轮历史");
        }
        for (List<AgentMessage> turn : kept) {
            out.addAll(turn);
        }
        if (kept.size() < turns.size()) {
            log.info("上下文预算不足,丢掉了 {} 轮较旧历史", turns.size() - kept.size());
        }
        return out;
    }

    private static List<List<AgentMessage>> splitTurns(List<AgentMessage> messages) {
        List<List<AgentMessage>> turns = new ArrayList<>();
        for (AgentMessage message : messages) {
            if ("user".equals(message.role()) || turns.isEmpty()) {
                turns.add(new ArrayList<>());
            }
            turns.get(turns.size() - 1).add(message);
        }
        return turns;
    }

    private static int length(AgentMessage message) {
        return message.content() == null ? 0 : message.content().length();
    }
}
