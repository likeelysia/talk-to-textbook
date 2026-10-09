package io.github.likeelysia.formalagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源名解析:用户手打的名字 → 知识库里真实存在的来源名。
 *
 * <p>测的是"宁可拒绝也别删错书"这条线:匹配不到要报错、匹配到多个也必须报错。
 */
class SourceResolverTest {

    /** 只要 sources() 的最小假实现 —— 解析逻辑用不到其余方法。 */
    private static KnowledgeStore storeOf(String... sources) {
        List<KnowledgeItem> items = new ArrayList<>();
        for (String s : sources) {
            items.add(new KnowledgeItem(s + "|k", "k", "d", s, null, "text"));
        }
        return new KnowledgeStore() {
            @Override
            public void addAll(List<KnowledgeItem> newItems) {
            }

            @Override
            public List<KnowledgeItem> all() {
                return List.copyOf(items);
            }

            @Override
            public List<KnowledgeItem> search(String query, int topK) {
                return List.of();
            }

            @Override
            public List<String> sources() {
                return items.stream().map(KnowledgeItem::source).distinct().toList();
            }

            @Override
            public int removeBySource(String source) {
                return 0;
            }

            @Override
            public int replaceSource(String source, List<KnowledgeItem> newItems) {
                return 0;
            }

            @Override
            public Map<String, Integer> countsBySource() {
                return Map.of();
            }
        };
    }

    @Test
    @DisplayName("全名精确命中,原样返回")
    void exactMatch() {
        assertEquals("物理.pdf", SourceResolver.resolve(storeOf("物理.pdf", "化学.pdf"), "物理.pdf"));
    }

    @Test
    @DisplayName("只写一部分:唯一命中就放行(不区分大小写)")
    void partialMatchWhenUnique() {
        KnowledgeStore store = storeOf("PDF_常用物理常量.pdf", "Java编程思想.pdf");

        assertEquals("PDF_常用物理常量.pdf", SourceResolver.resolve(store, "物理常量"));
        assertEquals("Java编程思想.pdf", SourceResolver.resolve(store, "java编程"));
    }

    @Test
    @DisplayName("匹配到多个 → 拒绝执行,并列出候选(防删错书)")
    void ambiguousMatchIsRejected() {
        KnowledgeStore store = storeOf("物理上.pdf", "物理下.pdf");

        AgentException e = assertThrows(AgentException.class,
                () -> SourceResolver.resolve(store, "物理"));

        assertTrue(e.getMessage().contains("多个来源"), e.getMessage());
        assertTrue(e.getMessage().contains("物理上.pdf") && e.getMessage().contains("物理下.pdf"),
                "候选要都列出来:" + e.getMessage());
    }

    @Test
    @DisplayName("匹配不到 → 报错并列出全部现有来源")
    void noMatchListsExistingSources() {
        KnowledgeStore store = storeOf("物理.pdf");

        AgentException e = assertThrows(AgentException.class,
                () -> SourceResolver.resolve(store, "化学"));

        assertTrue(e.getMessage().contains("没有匹配"), e.getMessage());
        assertTrue(e.getMessage().contains("物理.pdf"), "要把现有来源告诉他:" + e.getMessage());
    }

    @Test
    @DisplayName("空/空白/空库 → 一律报错(绝不当成\"清库\")")
    void blankIsRejected() {
        KnowledgeStore store = storeOf("物理.pdf");

        assertThrows(AgentException.class, () -> SourceResolver.resolve(store, null));
        assertThrows(AgentException.class, () -> SourceResolver.resolve(store, "   "));
        assertThrows(AgentException.class, () -> SourceResolver.resolve(storeOf(), "物理"));
    }
}
