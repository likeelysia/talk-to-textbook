package io.github.likeelysia.formalagent.memory;

/**
 * 提炼器吐出来的一条「候选记忆」:原文 + 模型给的「值得记」分数。
 *
 * <p><b>为什么带分数</b>:分数不只是用来"筛掉不值得记的",还用来区分
 * <b>"用户明确要求记住的"</b> —— 提示词里让它给 {@code 1.0}。
 * 这类条目入库时直接<b>钉住</b>({@code pinned}),以后记忆域满了也不会被淘汰。
 *
 * <p><b>⚠️ 命名陷阱</b>:这里叫 {@code mustRemember()} 而不是 {@code isMustRemember()}
 * —— 被 Jackson 当成 JSON 属性的只有 {@code getXxx()} / {@code isXxx()} 形式的无参方法,
 * 并且这个类型只在内网传递、不落盘,不用太紧张,但规矩统一着来。
 */
public record MemoryCandidate(String text, double keep) {

    /** 模型没给分数时的默认值:不好也不坏,留着(第二道闸只筛低于阈值的)。 */
    public static final double KEEP_DEFAULT = 0.5;

    /** 提醒模型给"用户明确要求记住的"打这个分 —— 入库时会被钉住。 */
    public static final double KEEP_MUST = 1.0;

    public MemoryCandidate {
        text = text == null ? "" : text.strip();
    }

    /** 只有原文(没有分数)的候选。 */
    public static MemoryCandidate of(String text) {
        return new MemoryCandidate(text, KEEP_DEFAULT);
    }

    /** 该不该钉住:用户亲口说"记住这个"的,模型会打满分。 */
    public boolean mustRemember() {
        return keep >= KEEP_MUST;
    }
}
