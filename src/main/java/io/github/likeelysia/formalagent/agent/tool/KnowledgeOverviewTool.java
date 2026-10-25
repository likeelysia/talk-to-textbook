package io.github.likeelysia.formalagent.agent.tool;

import io.github.likeelysia.formalagent.agent.Tool;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 工具二:知识库"全局概况"(总条数 + 各来源分布)。回答"教材里都有什么/多少"这类全局问题。 */
@Component
public class KnowledgeOverviewTool implements Tool {

    private final KnowledgeStore store;

    public KnowledgeOverviewTool(KnowledgeStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "knowledge_overview";
    }

    @Override
    public String description() {
        return "查看知识库整体情况:一共多少条知识点、来自哪几本教材(每本贡献多少条)。"
                + "当用户问“教材里都有什么/一共多少内容”这类全局问题时使用(普通检索回答不了这种问题)。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        int total = store.count();
        if (total == 0) return "知识库是空的(还没有导入任何教材)。";

        Map<String, Integer> bySource = store.countsBySource();

        StringBuilder sb = new StringBuilder("知识库共 ").append(total).append(" 条知识点,来源:\n");
        bySource.forEach((source, count) ->
                sb.append("- ").append(source).append(":").append(count).append(" 条\n"));
        return sb.toString();
    }
}
