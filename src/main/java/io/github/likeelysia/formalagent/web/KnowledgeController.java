package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.SourceResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
        int safeSample = Math.max(0, Math.min(sample, 100));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", store.count());
        body.put("sources", store.sources());
        body.put("sourceCounts", store.countsBySource());
        body.put("sample", store.page(0, safeSample).stream()
                .map(k -> k.name() + " / " + k.location())
                .toList());
        return body;
    }

    /** 只查总数,不复制全库。 */
    @GetMapping("/knowledge/count")
    public Map<String, Object> count() {
        return Map.of("count", store.count());
    }

    /** 分页读取知识点,给管理端/大库检查用,不触发全库复制。 */
    @GetMapping("/knowledge/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "20") int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", store.count());
        body.put("offset", safeOffset);
        body.put("limit", safeLimit);
        body.put("items", store.page(safeOffset, safeLimit));
        return body;
    }

    /** 按稳定 id 读取单条;找不到返回 404。 */
    @GetMapping("/knowledge/item")
    public KnowledgeItem item(@RequestParam("id") String id) {
        KnowledgeItem item = store.get(id);
        if (item == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "knowledge item not found");
        return item;
    }

    /** 向量补齐状态;管理端用 X-Admin-Key 调用。 */
    @GetMapping("/knowledge/vectors/status")
    public Map<String, Object> vectorStatus() {
        int total = store.count();
        int missing = store.missingVectorCount();
        return Map.of("total", total, "missing", missing, "complete", missing == 0);
    }

    /**
     * 显式补一批向量;每批落盘后返回 remaining,可重复调用直到 complete=true。
     * 查询路径不会自动触发它。
     */
    @PostMapping("/knowledge/vectors/backfill")
    public KnowledgeStore.VectorBackfillResult backfillVectors(
            @RequestParam(defaultValue = "1000") int limit) {
        return store.backfillVectors(Math.max(1, Math.min(limit, 2000)));
    }

    /** 按来源删除;name 可只写文件名的一部分,但只有唯一命中才动手(见 {@link SourceResolver})。 */
    @DeleteMapping("/knowledge/source")
    public Map<String, Object> removeSource(@RequestParam("name") String name) {
        String source = SourceResolver.resolve(store, name);
        int removed = store.removeBySource(source);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("removed", removed);
        body.put("count", store.count());
        return body;
    }
}
