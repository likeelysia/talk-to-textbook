package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.agent.StreamSink;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.service.AssistantService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式问答:POST /api/agent/stream(SSE)。
 *
 * <p><b>为什么不改老的 /api/agent</b>:那个接口是"一问一答"的稳定契约,命令行、脚本、老测试都在用。
 * 新增一个端点,新旧并存,谁也别打扰谁 —— 前端切过去,其它调用方一动不动。
 *
 * <p>事件协议(一条一个事件,{@code event: 名字} + {@code data: json}):
 * <pre>
 *   meta   {"conversationId":"default"}                  开场,告诉前端这次算哪个会话
 *   status {"text":"正在查资料(search_knowledge)…"}      进度提示(工具轮会有)
 *   reset  {}                                             刚才吐的不算了(那一轮其实是要调工具)
 *   delta  {"text":"位移"}                                新吐出来的一小段正文
 *   done   {"answer":"…","steps":2,"trace":[...],...}     结束,拿到的才是"最终答案"
 *   error  {"message":"…"}                                出错
 * </pre>
 *
 * <p>另外每 15 秒会插一条 SSE 注释({@code : ping})当心跳,防止中间代理掐掉空闲连接。
 *
 * <p>会话 id 的校验放在<b>返回 emitter 之前</b>:id 不存在时仍然走老的 400 + JSON 错误,
 * 而不是"流都建好了再说一句错误" —— 前端处理起来简单得多。
 */
@RestController
@RequestMapping("/api")
public class AgentStreamController {

    /** SseEmitter 的超时:多轮工具调用 + 慢模型可能跑很久,给足 5 分钟。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    private final AssistantService assistant;
    private final ConversationService conversations;
    private final SseStreamer streams;

    public AgentStreamController(AssistantService assistant, ConversationService conversations,
                                 SseStreamer streams) {
        this.assistant = assistant;
        this.conversations = conversations;
        this.streams = streams;
    }

    /** 请求体沿用 {@link AgentController.AgentRequest}(question + conversationId),同一个东西不重复定义。 */
    @PostMapping(value = "/agent/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentController.AgentRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new AgentException("question 不能为空");
        }
        String conversationId = conversations.requireExisting(req.conversationId());

        SseEmitter emitter = newEmitter();
        streams.start(emitter, out -> pipe(out, conversationId, req.question()));
        return emitter;
    }

    /**
     * 建一个 emitter。单独抽出来是为了测试能换成"记账用的 emitter" ——
     * 事件顺序是这个接口的核心契约,
     * 不测的话哪天顺手调换两行都没人发现。
     */
    protected SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    /** 真正的推送:把 StreamSink 的三个动作翻译成 SSE 事件,结束时补一条 done。 */
    private void pipe(SseEmitter out, String conversationId, String question) {
        SseStreamer.sendEvent(out, "meta", Map.of("conversationId", conversationId));

        AssistantService.Turn turn = assistant.askStream(conversationId, question, question, new StreamSink() {
            @Override
            public void onDelta(String text) {
                SseStreamer.sendEvent(out, "delta", Map.of("text", text));
            }

            @Override
            public void onReset() {
                SseStreamer.sendEvent(out, "reset", Map.of());
            }

            @Override
            public void onStatus(String text) {
                SseStreamer.sendEvent(out, "status", Map.of("text", text));
            }
        });

        AgentResult result = turn.result();
        SseStreamer.sendEvent(out, "done", new AgentController.AgentResponse(
                result.answer(), result.steps(), result.trace(), turn.conversationId()));
    }
}
