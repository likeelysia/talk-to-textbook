package io.github.likeelysia.formalagent.agent;

/**
 * 流式问答的"接收端":Agent 主循环一边跑,一边把进展推给它,由它负责送出去(HTTP SSE)。
 *
 * <p>为什么不让 AgentService 直接操作 SseEmitter:那样这一层就被绑死在 Web 上,
 * 命令行/测试里想复用流式就没法用了。这里只留三个动作,谁实现都行。
 */
public interface StreamSink {

    /** 模型吐出了一小段正文。 */
    void onDelta(String text);

    /**
     * <b>把这一轮已经吐出去的内容作废。</b>
     *
     * <p>为什么需要它:模型在"决定调工具"的那一轮里,常常先自言自语半句
     * (比如"我查一下……"),可它不是答案。这时前端得把那半句擦掉,等真正
     * 的答案再重新逐字出现 —— 否则用户会看到"半截自言自语 + 正文"粘在一起。
     */
    void onReset();

    /** 状态提示(例如"正在查资料")。默认什么都不做,不关心过程就别实现。 */
    default void onStatus(String text) {
    }
}
