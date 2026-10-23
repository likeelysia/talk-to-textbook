package io.github.likeelysia.formalagent.llm;

/**
 * 标记“当前线程的模型调用是不是后台任务”。
 *
 * <p>后台提炼/对账可以占一个总名额，但最多只占一个后台名额，避免它把交互问答的名额全吃光。
 * 用 ThreadLocal 而不是给每个方法加参数，是为了不把“调度优先级”污染到业务接口里。
 */
public final class ModelCallContext {

    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ModelCallContext() {
    }

    public static boolean isBackground() {
        return Boolean.TRUE.equals(BACKGROUND.get());
    }

    /** 进入后台作用域;用 try-with-resources 关闭。 */
    public static Scope background() {
        boolean previous = isBackground();
        BACKGROUND.set(Boolean.TRUE);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final boolean previous;

        private Scope(boolean previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            BACKGROUND.set(previous);
        }
    }
}
