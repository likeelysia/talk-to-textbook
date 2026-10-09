package io.github.likeelysia.formalagent.extract;

import io.github.likeelysia.formalagent.doc.DocPipeline;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.likeelysia.formalagent.doc.TextChunk;

/** 教材摄取:一个文件 → 知识点(带出处)→ 存进知识库。 */
@Component
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DocPipeline pipeline;
    private final KnowledgeExtractor extractor;
    private final KnowledgeStore store;

    public IngestService(DocPipeline pipeline, KnowledgeExtractor extractor, KnowledgeStore store) {
        this.pipeline = pipeline;
        this.extractor = extractor;
        this.store = store;
    }

    /** 提取并入库(同名同 id 覆盖,旧条目保留);来源 = 文件名。 */
    public List<KnowledgeItem> ingest(Path file) {
        return ingest(file, fileNameOf(file), false);
    }

    /** 同上,并指定要不要重灌。 */
    public List<KnowledgeItem> ingest(Path file, boolean replace) {
        return ingest(file, fileNameOf(file), replace);
    }

    /**
     * 提取并入库。
     *
     * @param source  <b>来源标识</b>(知识库里靠它区分"哪本书")。上传接口要传<b>原始文件名</b>:
     *                落盘的文件名带时间戳、每次都不一样,拿它当来源的话,同一本书重传就变成"另一本书"
     *                —— 既覆盖不了旧的,重灌也找不到旧的
     * @param replace {@code true} = <b>重灌</b>:先按来源删掉这本书的旧条目,再写入新的。
     *                教材改版 / 知识点改名后,只靠"同 id 覆盖"会把新版里删掉的知识点永远留在库里
     */
    public List<KnowledgeItem> ingest(Path file, String source, boolean replace) {
        String sourceName = (source == null || source.isBlank()) ? fileNameOf(file) : source.strip();

        Map<String, KnowledgeItem> items = new LinkedHashMap<>();    // name → item(同名去重)
        List<String> failedChunks = new ArrayList<>();
        for (TextChunk chunk : pipeline.load(file)) {
            List<KnowledgePoint> points;
            try {
                points = extractor.extract(chunk.text());
            } catch (AgentException e) {                            // ← 单块提炼失败不拖垮整本
                failedChunks.add(chunk.location());
                log.warn("提炼 {} 失败,已跳过:{}", chunk.location(), e.getMessage());
                continue;
            }
            for (KnowledgePoint p : points) {
                items.putIfAbsent(p.name(), itemOf(sourceName, p, chunk.location()));
            }
        }
        if (!failedChunks.isEmpty()) {
            log.warn("有 {} 块没提炼成功:{}", failedChunks.size(), failedChunks);
        }

        List<KnowledgeItem> result = List.copyOf(items.values());
        if (replace) store.replaceSource(sourceName, result);
        else store.addAll(result);
        return result;
    }

    private static String fileNameOf(Path file) {
        return file.getFileName().toString();
    }

    /**
     * 造条目:知识点 → KnowledgeItem(id / 出处 / 来源 / 类型)。
     *
     * <p>整本灌({@link #ingest(Path, String, boolean)})与分块断点灌
     * ({@link CheckpointIngestService})共用这一条规则 —— id、出处格式不靠两处各写一遍,
     * 否则“重灌只覆盖同一本书”这个前提会不知不觉被破坏。
     */
    static KnowledgeItem itemOf(String sourceName, KnowledgePoint p, String location) {
        return new KnowledgeItem(sourceName + "|" + p.name(), p.name(), p.detail(),
                sourceName, location, typeOf(sourceName));
    }

    /** 按扩展名判类型(不管路径,只看名字后缀)。 */
    static String typeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "pdf";
        return "text";
    }
}