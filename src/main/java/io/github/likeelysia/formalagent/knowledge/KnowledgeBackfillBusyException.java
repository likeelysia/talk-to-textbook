package io.github.likeelysia.formalagent.knowledge;

/** 已有向量补齐任务在途时,新的补齐请求快速失败;不与查询锁共用,也不排队。 */
public class KnowledgeBackfillBusyException extends RuntimeException {
    public KnowledgeBackfillBusyException() {
        super("向量补齐正在进行,请稍后重试");
    }
}
