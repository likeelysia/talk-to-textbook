package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.conversation.ChatMessage;
import java.util.List;

/**
 * 从「一段对话」里挑出值得长期记住的东西。
 *
 * <p><b>为什么返回 Result 而不是裸 List</b>:必须区分三种结果 ——
 * <ul>
 *   <li>成功且有候选 → 正常入库;</li>
 *   <li>成功但没有值得记的 → 可以推进处理水位,避免重复扫描;</li>
 *   <li>临时失败(模型超时/解析失败/存储失败) → <b>不能</b>推进水位,否则这段对话永远不再提炼。</li>
 * </ul>
 * 以前失败被转成空列表,水位照推,等于把"没处理"记成"处理过了"。
 */
public interface MemoryExtractor {

    /**
     * @param candidates 提炼出的候选(可能为空)
     * @param success    false = 临时失败,调用方应保留水位并重试
     */
    record Result(List<MemoryCandidate> candidates, boolean success) {

        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static Result ok(List<MemoryCandidate> candidates) {
            return new Result(candidates, true);
        }

        public static Result empty() {
            return new Result(List.of(), true);
        }

        public static Result failed() {
            return new Result(List.of(), false);
        }
    }

    /**
     * @param turns 还没提炼过的对话(按时间先后,一条用户消息开启一轮)
     * @return 成功/空/失败三态;失败不要抛异常,让调用方走退避重试
     */
    Result extract(List<ChatMessage> turns);
}
