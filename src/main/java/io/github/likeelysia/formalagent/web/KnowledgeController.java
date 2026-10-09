package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.SourceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库概况与来源管理。
 *
 * <pre>
 *   GET    /api/knowledge?sample=5           概况:总条数、来源清单、每个来源的条数、抽样
 *   DELETE /api/knowledge/source?name=xxx    按来源删除(管理端,受 X-Admin-Key 保护)
 * </pre>
 *
 * <p>删除必须走接口而不是只给 CLI:<b>应用在跑的时候,它的知识库全在内存里</b>,
 * 直接在磁盘上删文件会被它下一次写盘盖回去。接口删 = 内存和磁盘一起改,不会打架。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeStore store;

    public KnowledgeController(KnowledgeStore store) {
        this.store = store;
    }

    @GetMapping("/knowledge")
    public Map<String, Object> knowledge(@RequestParam(defaultValue = "5") int sample) {
        List<KnowledgeItem> all = store.all();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", all.size());
        body.put("sources", all.stream().map(KnowledgeItem::source).distinct().toList());
        body.put("sourceCounts", store.countsBySource());
        body.put("sample", all.stream()
                .limit(Math.max(0, sample))
                .map(k -> k.name() + " / " + k.location())
                .toList());
        return body;
    }

    /** 按来源删除;name 可只写文件名的一部分,但只有唯一命中才动手(见 {@link SourceResolver})。 */
    @DeleteMapping("/knowledge/source")
    public Map<String, Object> removeSource(@RequestParam("name") String name) {
        String source = SourceResolver.resolve(store, name);
        int removed = store.removeBySource(source);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("removed", removed);
        body.put("count", store.all().size());
        return body;
    }
}
