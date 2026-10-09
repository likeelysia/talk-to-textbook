package io.github.likeelysia.formalagent.conversation;

import java.util.List;

/**
 * 会话里的一条消息(role + 内容 + 时间 + 可选的工具轨迹)。
 *
 * <p>和老的 {@code chat.Message} 的区别:
 * <ul>
 *   <li>多了 {@code createdAt} —— 列表排序、"最近聊了什么"要用;</li>
 *   <li>多了 {@code trace} —— Agent 这一轮调了哪些工具,回看历史时能一起还原;</li>
 *   <li>用 record(不可变)—— 见 {@link Conversation} 的说明。</li>
 * </ul>
 *
 * <p>⚠️ {@code trace} 允许为 null(纯文本提问时没有工具轨迹),
 * 读取方要 null 安全 —— 这是"加字段但老数据没有它"的常见形态。
 *
 * <p><b>⚠️ 命名陷阱(踩过):</b>这里的方法叫 {@code fromUser()} / {@code fromAssistant()},
 * 而<b>不是</b> {@code isUser()} —— 因为 Jackson 会把任何 {@code isXxx()} / {@code getXxx()}
 * 形式的<b>无参方法</b>都当成"一个 JSON 属性"。叫 {@code isUser()} 的话,写出去的 JSON 里会多出
 * {@code "user":true},回读时它又是个"未知字段",直接反序列化失败。
 * 想写 {@code isXxx()} 又要躲开,就得挂 {@code @JsonIgnore};但更省事的做法是别用那个前缀。
 */
public record ChatMessage(String role, String content, long createdAt, List<String> trace) {

    /** 紧凑构造器:把 null 归一化,别让下游到处判空。 */
    public ChatMessage {
        trace = (trace == null || trace.isEmpty()) ? null : List.copyOf(trace);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, System.currentTimeMillis(), null);
    }

    public static ChatMessage assistant(String content, List<String> trace) {
        return new ChatMessage("assistant", content, System.currentTimeMillis(), trace);
    }

    /** 是用户发的?(⚠️ 不能叫 isUser —— 见类注释的命名陷阱) */
    public boolean fromUser() {
        return "user".equals(role);
    }

    public boolean fromAssistant() {
        return "assistant".equals(role);
    }
}
