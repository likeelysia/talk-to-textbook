package io.github.likeelysia.formalagent.exception;

/** 资源繁忙(模型并发满/排队满):让接口回 503,而不是 400 参数错误。 */
public class OverloadedException extends AgentException {
    public OverloadedException(String message) {
        super(message);
    }
}
