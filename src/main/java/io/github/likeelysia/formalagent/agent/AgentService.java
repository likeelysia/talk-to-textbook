package io.github.likeelysia.formalagent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolCall;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 主循环(ReAct):把"检索"从写死的步骤,变成模型手里的工具。
 *
 * <pre>
 *   模型:我要调 search_knowledge("闭合曲线 位移")     ← 它自己决定
 *     ↓ 我们执行工具,把结果喂回去
 *   模型:信息够了,这是答案(+ 出处)
 * </pre>
 *
 * <p>安全网:{@link #MAX_STEPS} 限制轮数 —— 模型有可能反复调工具绕圈,不设上限会把 token 烧光。
 *
 * <p><b>流式与非流式共用同一份循环</b>:差别只有"这一轮怎么从模型那儿拿回复"
 * (一次性 vs 逐字回调)。所以这里把循环抽成一个私有方法,由 {@link TurnSource} 决定取回复的方式 ——
 * 两套逻辑各写一遍的话,以后改循环(加工具、改步数上限、改日志)必然漏改其中一份。
 */
@Component
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 最多几轮"思考-行动"。 */
    private static final int MAX_STEPS = 6;

    // 系统提示词已外置到 resources/prompts/agent-system.txt(改文案不用重新理解 Java)

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public AgentService(LlmClient llm, List<Tool> tools, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
        for (Tool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    /** 跑一轮 ReAct(不带历史上下文)。 */
    public AgentResult run(String question) {
        return run(question, List.of());
    }

    /**
     * 跑一轮 ReAct:思考 → 调工具 → 再思考 → … 直到给出答案(或撞上步数上限)。
     *
     * @param history 这个会话“之前聊过什么”(滚动摘要 + 最近几轮)。插入位置很讲究:
     *                <b>系统提示词之后、当前提问之前</b> —— 放最前面会把系统规则顶掉,
     *                放最后又会被模型当成“用户刚说的话”。历史里只回放 role + 文本,
     *                <b>不重放旧的 tool_calls</b>:工具调用消息必须和它的 tool_call_id 结果成对出现,
     *                少一条 API 直接报错。
     */
    public AgentResult run(String question, List<AgentMessage> history) {
        return loop(question, history, null, (messages, definitions) -> llm.chatWithTools(messages, definitions));
    }

    /**
     * 流式版:答案边生成边经过 {@code sink} 推出去,返回值仍是完整结果(用于落库 / trace)。
     */
    public AgentResult runStream(String question, List<AgentMessage> history, StreamSink sink) {
        return loop(question, history, sink,
                (messages, definitions) -> llm.chatWithToolsStream(messages, definitions, sink::onDelta));
    }

    /** "这一轮怎么从模型那儿拿到回复" —— 一次性的,还是逐字回调的。 */
    @FunctionalInterface
    private interface TurnSource {
        AssistantTurn next(List<AgentMessage> messages, List<ToolDefinition> definitions);
    }

    /** ReAct 主循环:流式与非流式共用这一份;{@code sink} 为 null 表示不流式。 */
    private AgentResult loop(String question, List<AgentMessage> history, StreamSink sink, TurnSource source) {
        if (question == null || question.isBlank()) {
            throw new AgentException("问题不能为空");
        }

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(Prompts.get("agent-system")));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(AgentMessage.user(question));

        List<ToolDefinition> definitions = tools.values().stream().map(Tool::definition).toList();
        List<String> trace = new ArrayList<>();

        for (int step = 1; step <= MAX_STEPS; step++) {
            AssistantTurn turn = source.next(messages, definitions);
            if (!turn.wantsTools()) {                                  // 不需要工具 → 这就是最终答案
                return new AgentResult(turn.content() == null ? "" : turn.content(), trace, step);
            }

            // 它其实是要调工具 —— 刚才那半句"我查一下…"不是答案,作废重来
            if (sink != null) {
                sink.onReset();
            }
            messages.add(AgentMessage.assistantWithTools(turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                String name = call.function() == null ? "?" : call.function().name();
                String rawArgs = call.function() == null ? "" : call.function().arguments();
                if (sink != null) {
                    sink.onStatus("正在查资料(" + name + ")…");
                }
                String observation = execute(call);

                trace.add(name + "(" + rawArgs + ")");
                log.info("工具调用:{} → {}", trace.get(trace.size() - 1), abbreviate(observation));
                messages.add(AgentMessage.tool(call.id(), observation));
            }
        }

        log.warn("达到步数上限 {} 仍未给出答案", MAX_STEPS);
        return new AgentResult(
                "抱歉,我查了 " + MAX_STEPS + " 步还没能确定答案,请问得更具体一些。", trace, MAX_STEPS);
    }

    /** 执行一个工具调用;任何异常都变成"观察结果"还给模型(让它自己想办法),而不是把整轮弄崩。 */
    private String execute(ToolCall call) {
        String name = call.function() == null ? null : call.function().name();
        Tool tool = name == null ? null : tools.get(name);
        if (tool == null) {
            return "没有名为 " + name + " 的工具。";
        }
        try {
            String raw = call.function().arguments();
            Map<String, Object> args = (raw == null || raw.isBlank())
                    ? Map.of()
                    : mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            return tool.execute(args);
        } catch (Exception e) {
            return "工具执行失败:" + e.getMessage();
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "...";
    }
}
