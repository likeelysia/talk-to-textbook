package io.github.likeelysia.formalagent.memory;

/**
 * 提炼器吐出来的一条「候选记忆」:原文 + 模型给的「值得记」分数 + 两个明确意图标记。
 *
 * <p><b>为什么把"重要"和"授权"拆开</b>:
 * <ul>
 *   <li>{@code keep} = 内容值不值得保存;</li>
 *   <li>{@code explicitRemember} = 用户是否明确要求"记住这个" → 入库/命中已有条目时钉住;</li>
 *   <li>{@code explicitForget} = 用户是否明确要求"忘掉这个" → 允许删除,包括钉住的条目。</li>
 * </ul>
 * 以前用 {@code keep=1.0} 一个数同时表达两件事,结果"重要"被误当成"用户授权删除/钉住"。
 * 现在 1.0 仍兼容为 explicitRemember,但不再有删除授权。
 */
public record MemoryCandidate(String text, double keep, boolean explicitRemember, boolean explicitForget) {

    /** 模型没给分数时的默认值:不好也不坏,留着(第二道闸只筛低于阈值的)。 */
    public static final double KEEP_DEFAULT = 0.5;

    /** 兼容旧提示词:模型给 1.0 仍视为"用户明确要求记住"(但不等于授权删除)。 */
    public static final double KEEP_MUST = 1.0;

    public MemoryCandidate {
        text = text == null ? "" : text.strip();
        keep = Math.max(0, Math.min(1, keep));
    }

    /** 兼容旧调用:keep=1.0 仍自动视为 explicitRemember。 */
    public MemoryCandidate(String text, double keep) {
        this(text, keep, keep >= KEEP_MUST, false);
    }

    /** 只有原文(没有分数)的候选。 */
    public static MemoryCandidate of(String text) {
        return new MemoryCandidate(text, KEEP_DEFAULT, false, false);
    }

    /** 用户明确要求记住的一条。 */
    public static MemoryCandidate remember(String text) {
        return new MemoryCandidate(text, KEEP_MUST, true, false);
    }

    /** 用户明确要求忘掉的一条。 */
    public static MemoryCandidate forget(String text) {
        return new MemoryCandidate(text, KEEP_DEFAULT, false, true);
    }

    /** 该不该钉住:用户亲口说"记住这个"的。 */
    public boolean mustRemember() {
        return explicitRemember || keep >= KEEP_MUST;
    }

    /** 该不该允许删除(包括钉住的条目)。 */
    public boolean wantsForget() {
        return explicitForget;
    }
}
