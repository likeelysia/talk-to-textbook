package io.github.likeelysia.formalagent.knowledge;

import java.util.List;
import java.util.Locale;

import io.github.likeelysia.formalagent.exception.AgentException;

/**
 * 把"用户手打的来源名"解析成知识库里真实存在的来源名。
 *
 * <p>为什么需要它:知识库里的来源是<b>上传时的完整文件名</b>(可能很长、带中文、带日期前缀),
 * 让人一个字不差地敲出来不现实。所以:
 * <ol>
 *   <li>先按<b>全名精确匹配</b>;</li>
 *   <li>没中再按<b>包含</b>匹配,但<b>必须唯一命中</b>才放行;</li>
 *   <li>命中 0 个 → 报错并列出所有来源;命中多个 → 报错并列出候选,让人写全。</li>
 * </ol>
 * 第 3 条是关键:<b>模糊匹配 + 直接删除 = 事故</b>(想删 A 结果删了 A、B 两本),
 * 所以歧义时宁可拒绝执行。
 */
public final class SourceResolver {

    private SourceResolver() {
    }

    /** @throws AgentException 输入为空 / 匹配不到 / 匹配到多个 */
    public static String resolve(KnowledgeStore store, String nameOrPart) {
        if (nameOrPart == null || nameOrPart.isBlank()) {
            throw new AgentException("来源名不能为空");
        }
        String key = nameOrPart.strip();
        List<String> sources = store.sources();

        if (sources.contains(key)) return key;                       // ① 全名

        String needle = key.toLowerCase(Locale.ROOT);                // ② 部分匹配(不区分大小写)
        List<String> hits = sources.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        if (hits.size() == 1) return hits.get(0);
        if (hits.isEmpty()) {
            throw new AgentException("知识库里没有匹配 \"" + key + "\" 的来源。现有来源:"
                    + describe(sources));
        }
        throw new AgentException("匹配到多个来源,请写完整一点:\n  " + String.join("\n  ", hits));
    }

    private static String describe(List<String> sources) {
        if (sources.isEmpty()) return "(空)";
        return "\n  " + String.join("\n  ", sources);
    }
}
