package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.agent.StreamSink;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.service.AssistantService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式接口的"事件协议"测试:用记账用的 emitter 把每条事件记下来 ——
 * 顺序是这个接口的核心契约,不测的话哪天顺手调换两行都没人发现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("流式问答接口(SSE)")
class AgentStreamControllerTest {

    @Mock
    private AssistantService assistant;

    @Mock
    private ConversationService conversations;

    private SseStreamer streams;
    private RecordingEmitter out;
    private AgentStreamController controller;

    /** 记账用的 emitter:把每次 send 的事件名 + 数据记下来,不真往网络上写。 */
    static class RecordingEmitter extends SseEmitter {

        record Event(String name, Object data) {
        }

        final List<Event> events = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            String name = null;
            List<Object> payloads = new ArrayList<>();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                Object data = part.getData();
                if (data instanceof String s && s.startsWith("event:")) {
                    // Spring 把 "event:xxx\ndata:" 当成一段发出来,名字夹在中间
                    int newline = s.indexOf('\n');
                    name = (newline < 0 ? s.substring("event:".length()) : s.substring("event:".length(), newline)).trim();
                } else {
                    payloads.add(data);
                }
            }
            events.add(new Event(name, payloads.isEmpty() ? null : payloads.get(0)));
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        Object dataOf(String eventName) {
            return events.stream()
                    .filter(e -> eventName.equals(e.name()))
                    .map(Event::data)
                    .findFirst()
                    .orElse(null);
        }
    }

    @BeforeEach
    void setUp() {
        streams = new SseStreamer();
        out = new RecordingEmitter();
        controller = new AgentStreamController(assistant, conversations, streams) {
            @Override
            protected SseEmitter newEmitter() {
                return out;                       // 换成记账用的,好断言事件顺序
            }
        };
        when(conversations.requireExisting(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return id == null || String.valueOf(id).isBlank() ? "default" : id;
        });
    }

    @AfterEach
    void tearDown() {
        streams.shutdown();
    }

    @Test
    @DisplayName("完整一轮:meta → delta… → done,顺序不能乱")
    void emitsDeltasThenDone() {
        when(assistant.askStream(eq("c1"), anyString(), anyString(), any())).thenAnswer(invocation -> {
            StreamSink sink = invocation.getArgument(3);
            sink.onDelta("位移");
            sink.onDelta("是零。");
            return new AssistantService.Turn(
                    new AgentResult("位移是零。", List.of("search_knowledge({})"), 2), "c1");
        });

        controller.stream(new AgentController.AgentRequest("位移?", "c1"));

        await(() -> out.completed);
        assertThat(out.names()).containsExactly("meta", "delta", "delta", "done");
        assertThat(out.dataOf("meta")).isEqualTo(Map.of("conversationId", "c1"));
        assertThat(out.dataOf("delta")).isEqualTo(Map.of("text", "位移"));
        assertThat(out.dataOf("done")).isEqualTo(
                new AgentController.AgentResponse("位移是零。", 2, List.of("search_knowledge({})"), "c1"));
    }

    @Test
    @DisplayName("工具轮:status 提示 + reset 作废 —— 前端才知道那半句不算数")
    void emitsStatusAndReset() {
        when(assistant.askStream(eq("c1"), anyString(), anyString(), any())).thenAnswer(invocation -> {
            StreamSink sink = invocation.getArgument(3);
            sink.onDelta("我查一下");
            sink.onReset();
            sink.onStatus("正在查资料(search_knowledge)…");
            sink.onDelta("答案。");
            return new AssistantService.Turn(
                    new AgentResult("答案。", List.of("search_knowledge({})"), 2), "c1");
        });

        controller.stream(new AgentController.AgentRequest("位移?", "c1"));

        await(() -> out.completed);
        assertThat(out.names()).containsExactly("meta", "delta", "reset", "status", "delta", "done");
        assertThat(out.dataOf("reset")).isEqualTo(Map.of());
        assertThat(out.dataOf("status")).isEqualTo(Map.of("text", "正在查资料(search_knowledge)…"));
    }

    @Test
    @DisplayName("生成途中出错:发一条 error,而且流必须收尾(不能把请求挂死)")
    void sendsErrorEventOnFailure() {
        when(assistant.askStream(any(), anyString(), anyString(), any()))
                .thenThrow(new AgentException("模型挂了"));

        controller.stream(new AgentController.AgentRequest("位移?", null));

        await(() -> out.completed);
        assertThat(out.names()).containsExactly("meta", "error");
        assertThat(out.dataOf("error")).isEqualTo(Map.of("message", "模型挂了"));
    }

    @Test
    @DisplayName("空问题:在建流之前就拒绝(照旧是 400,而不是流里的一条 error)")
    void rejectsBlankQuestionBeforeOpeningStream() {
        assertThatThrownBy(() -> controller.stream(new AgentController.AgentRequest("  ", null)))
                .isInstanceOf(AgentException.class);

        assertThat(out.events).isEmpty();
    }

    @Test
    @DisplayName("会话不存在:同样在建流之前就失败")
    void rejectsUnknownConversationBeforeOpeningStream() {
        when(conversations.requireExisting("nope")).thenThrow(new AgentException("会话不存在:nope"));

        assertThatThrownBy(() -> controller.stream(new AgentController.AgentRequest("位移?", "nope")))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");

        assertThat(out.events).isEmpty();
    }

    /** 等异步工作跑完(流式是另一个线程在推)。 */
    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等流式工作结束超时(5 秒)");
    }
}
