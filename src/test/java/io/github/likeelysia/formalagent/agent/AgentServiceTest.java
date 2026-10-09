package io.github.likeelysia.formalagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolCall;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Agent 循环测试:不联网,用"假工具 + 有剧本的假模型"验证 ReAct 控制流。 */
class AgentServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 假工具:固定返回一段"观察结果",并记住被调了几次、参数是什么。 */
    static class FakeTool implements Tool {
        int calls = 0;
        Map<String, Object> lastArgs;

        @Override public String name() { return "fake_tool"; }
        @Override public String description() { return "测试用"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }

        @Override
        public String execute(Map<String, Object> args) {
            calls++;
            lastArgs = args;
            return "观察结果:位移为零(出处:book 第 6 页)";
        }
    }

    /** 有剧本的假模型:按顺序吐出每一轮的回复(先调工具,最后给答案)。 */
    static class ScriptedLlm implements LlmClient {
        private final Deque<AssistantTurn> script = new ArrayDeque<>();
        List<AgentMessage> lastMessages;
        int turns = 0;

        ScriptedLlm(AssistantTurn... turns) {
            for (AssistantTurn t : turns) script.add(t);
        }

        @Override
        public String chat(List<Message> history, ChatOptions options) {
            return "unused";
        }

        @Override
        public AssistantTurn chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            this.lastMessages = new ArrayList<>(messages);
            this.turns++;
            return script.isEmpty() ? new AssistantTurn("(剧本结束)", null) : script.poll();
        }
    }

    private static ToolCall call(String args) {
        return new ToolCall("call_1", "function", new ToolCall.Function("fake_tool", args));
    }

    @Test
    @DisplayName("ReAct:先调工具 → 拿结果 → 再给答案")
    void runsToolThenAnswers() {
        FakeTool tool = new FakeTool();
        ScriptedLlm llm = new ScriptedLlm(
                new AssistantTurn(null, List.of(call("{\"query\":\"位移\"}"))),
                new AssistantTurn("位移是零。【出处】闭合曲线运动一周情形 —— book 第 6 页", null));
        AgentService agent = new AgentService(llm, List.of(tool), mapper);

        AgentResult result = agent.run("闭合曲线一周的位移?");

        assertEquals(1, tool.calls, "工具应被调用一次");
        assertEquals("位移", tool.lastArgs.get("query"), "参数应被解析出来");
        assertEquals(2, result.steps());
        assertTrue(result.answer().contains("位移是零"));
        assertEquals(1, result.trace().size());
        assertTrue(result.trace().get(0).startsWith("fake_tool("));

        // 喂回给模型的消息里,应当既有 assistant(tool_calls),也有 role=tool 的结果
        assertTrue(llm.lastMessages.stream().anyMatch(m -> "tool".equals(m.role())));
        assertTrue(llm.lastMessages.stream().anyMatch(m -> m.toolCalls() != null));
    }

    @Test
    @DisplayName("不需要工具时:一步直接回答")
    void answersDirectlyWhenNoToolNeeded() {
        FakeTool tool = new FakeTool();
        ScriptedLlm llm = new ScriptedLlm(new AssistantTurn("你好!我是教材助手。", null));
        AgentService agent = new AgentService(llm, List.of(tool), mapper);

        AgentResult result = agent.run("你好");

        assertEquals(0, tool.calls);
        assertEquals(1, result.steps());
        assertTrue(result.trace().isEmpty());
    }

    @Test
    @DisplayName("模型绕圈调工具 → 步数上限兜底,不会无限循环")
    void stopsAtMaxSteps() {
        FakeTool tool = new FakeTool();
        AssistantTurn alwaysCalls = new AssistantTurn(null, List.of(call("{}")));
        ScriptedLlm llm = new ScriptedLlm(alwaysCalls, alwaysCalls, alwaysCalls, alwaysCalls,
                alwaysCalls, alwaysCalls, alwaysCalls, alwaysCalls);
        AgentService agent = new AgentService(llm, List.of(tool), mapper);

        AgentResult result = agent.run("一直查");

        assertEquals(6, result.steps(), "应在上限处停下");
        assertTrue(result.answer().contains("还没能确定"));
    }

    @Test
    @DisplayName("工具参数不是合法 JSON → 当成观察结果还给模型,不崩")
    void handlesBadToolArguments() {
        FakeTool tool = new FakeTool();
        ScriptedLlm llm = new ScriptedLlm(
                new AssistantTurn(null, List.of(call("这不是JSON"))),
                new AssistantTurn("好的。", null));
        AgentService agent = new AgentService(llm, List.of(tool), mapper);

        AgentResult result = agent.run("随便问问");

        assertEquals(0, tool.calls, "参数解析失败,工具不该被执行");
        assertEquals("好的。", result.answer());
    }

    @Test
    @DisplayName("带历史:历史插在系统提示之后、当前提问之前")
    void injectsHistoryInTheRightPlace() {
        ScriptedLlm llm = new ScriptedLlm(new AssistantTurn("好的。", null));
        AgentService agent = new AgentService(llm, List.of(new FakeTool()), mapper);

        agent.run("那它呢?", List.of(AgentMessage.user("位移是多少?"),
                new AgentMessage("assistant", "零。", null, null)));

        List<AgentMessage> sent = llm.lastMessages;
        assertEquals("system", sent.get(0).role());
        assertEquals("位移是多少?", sent.get(1).content());
        assertEquals("零。", sent.get(2).content());
        assertEquals("那它呢?", sent.get(sent.size() - 1).content());
    }

    @Test
    @DisplayName("不带历史:消息表就是「系统提示 + 当前提问」两条")
    void withoutHistoryOnlyTwoMessagesGoOut() {
        ScriptedLlm llm = new ScriptedLlm(new AssistantTurn("好的。", null));
        AgentService agent = new AgentService(llm, List.of(new FakeTool()), mapper);

        agent.run("位移是多少?");

        assertEquals(2, llm.lastMessages.size());
    }

    @Test
    @DisplayName("工具抛异常 → 变成观察结果,循环继续")
    void toolFailureBecomesObservation() {
        Tool boom = new Tool() {
            @Override public String name() { return "fake_tool"; }
            @Override public String description() { return "x"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(Map<String, Object> args) { throw new IllegalStateException("boom"); }
        };
        ScriptedLlm llm = new ScriptedLlm(
                new AssistantTurn(null, List.of(call("{}"))),
                new AssistantTurn("工具挂了,我直说。", null));
        AgentService agent = new AgentService(llm, List.of(boom), mapper);

        AgentResult result = agent.run("x");

        assertEquals("工具挂了,我直说。", result.answer());
        assertEquals(2, result.steps());
    }
}
