package io.github.likeelysia.formalagent.knowledge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.Vectors;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JSON 文件实现:整个知识库就是一份 JSON 数组;启动读、写入存。
 *
 * <p><b>检索 = hybrid(混合)</b>:把两路分数加权合并 ——
 * <pre>
 *   score = 0.7 × 向量相似度(意思近不近) + 0.3 × 关键词命中率(字面像不像)
 * </pre>
 * 这样既能"换个说法也找得到"(向量),又能"精确名词一找一个准"(关键词)。
 * 两路都算不出来时才降级为纯关键词(embedding 服务没开的情况)。
 *
 * <p><b>阈值</b>:{@code score < fa.knowledge.min-score} 的直接丢掉 ——
 * 避免"问不相干的东西也硬塞几条给模型",从源头减少一本正经地胡说。阈值设 0 = 不过滤。
 *
 * <p>向量存放在 sidecar 文件({@code base.vectors.json}),带模型指纹;模型一换自动重算。
 */
@Component
public class JsonKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(JsonKnowledgeStore.class);

    /** 向量权重(剩下给关键词)。留成常量,等有真实评测数据再考虑外置。 */
    private static final double VECTOR_WEIGHT = 0.7;

    /** 向量 sidecar 的内容。 */
    record VectorFile(String model, int dim, Map<String, float[]> vectors) {
    }

    /** 排序用的临时结构:知识点 + 得分 + 原顺序(同分时保持稳定)。 */
    private record Scored(KnowledgeItem item, double score, int seq) {
    }

    private final ObjectMapper mapper;
    private final EmbeddingClient embedder;
    private final Path file;
    private final Path vectorFile;
    private final double minScore;
    private final Map<String, KnowledgeItem> items = new LinkedHashMap<>();   // id → item(保序 + 去重)
    private final Map<String, float[]> vectors = new LinkedHashMap<>();       // id → 向量

    public JsonKnowledgeStore(ObjectMapper mapper, EmbeddingClient embedder,
                              @Value("${fa.knowledge.file:knowledge/base.json}") String filePath,
                              @Value("${fa.knowledge.min-score:0}") double minScore) {
        this.mapper = mapper;
        this.embedder = embedder;
        this.file = Paths.get(filePath);
        this.minScore = minScore;
        // base.json → base.vectors.json(向量另存一份,别把知识库本体撑爆)
        this.vectorFile = Paths.get(filePath.replaceAll("\\.json$", "") + ".vectors.json");
        load();
    }

    // ---------------------------------------------------------------- 读 / 写

    private void load() {
        if (Files.exists(file)) {
            try (MappingIterator<KnowledgeItem> it =
                         mapper.readerFor(KnowledgeItem.class).readValues(file.toFile())) {
                while (it.hasNextValue()) {
                    KnowledgeItem item = it.nextValue();
                    items.put(item.id(), item);
                }
            } catch (IOException e) {
                throw new AgentException("读取知识库失败:" + file, e);
            }
        }
        loadVectors();
    }

    private void loadVectors() {
        if (!Files.exists(vectorFile)) return;
        String model = readVectorModel();
        if (model == null || !embedder.modelName().equals(model)) {
            if (model != null) {
                log.info("embedding 模型变了({} → {}),旧向量作废,下次检索时重算", model, embedder.modelName());
            }
            return;
        }
        try (JsonParser p = mapper.getFactory().createParser(vectorFile.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                log.warn("向量文件格式不对,将重算:{}", vectorFile);
                return;
            }
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                JsonToken token = p.nextToken();
                if ("vectors".equals(field) && token == JsonToken.START_OBJECT) {
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        String id = p.currentName();
                        p.nextToken();
                        if (items.containsKey(id)) {
                            vectors.put(id, mapper.readValue(p, float[].class));
                        } else {
                            p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("向量文件读取失败,将重算:{}", e.getMessage());
        }
    }

    /** 先只读 sidecar 的 model 字段;向量字段再按流解析,不构造整份中间对象。 */
    private String readVectorModel() {
        try (JsonParser p = mapper.getFactory().createParser(vectorFile.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) return null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("model".equals(field)) return p.getText();
                p.skipChildren();
            }
        } catch (IOException e) {
            log.warn("向量文件读取失败,将重算:{}", e.getMessage());
        }
        return null;
    }

    @Override
    public void addAll(List<KnowledgeItem> newItems) {
        for (KnowledgeItem item : newItems) items.put(item.id(), item);   // 同 id 覆盖
        persist();
    }

    @Override
    public List<KnowledgeItem> all() {
        return List.copyOf(items.values());
    }

    @Override
    public int count() {
        return items.size();
    }

    @Override
    public List<KnowledgeItem> page(int offset, int limit) {
        if (offset < 0 || limit <= 0) return List.of();
        return items.values().stream().skip(offset).limit(limit).toList();
    }

    @Override
    public KnowledgeItem get(String id) {
        return id == null ? null : items.get(id);
    }

    // ---------------------------------------------------------------- 删除 / 替换(按来源)

    @Override
    public List<String> sources() {
        return items.values().stream().map(KnowledgeItem::source).distinct().toList();
    }

    @Override
    public Map<String, Integer> countsBySource() {
        Map<String, Integer> counts = new LinkedHashMap<>();           // 保序,列表看着顺
        items.values().forEach(k -> counts.merge(k.source(), 1, Integer::sum));
        return counts;
    }

    @Override
    public int removeBySource(String source) {
        if (source == null || source.isBlank()) return 0;              // 防呆:空参数绝不等于"清库"
        int removed = (int) items.values().stream()
                .filter(k -> source.equals(k.source()))
                .count();
        if (removed == 0) return 0;

        items.values().removeIf(k -> source.equals(k.source()));
        purgeOrphanVectors();
        persist();
        persistVectors();                                             // 顺手把向量 sidecar 瘦身
        log.info("按来源删除 {} 条:{}", removed, source);
        return removed;
    }

    @Override
    public int replaceSource(String source, List<KnowledgeItem> newItems) {
        if (source == null || source.isBlank()) {
            throw new AgentException("来源为空,拒绝替换");
        }
        int removed = (int) items.values().stream()
                .filter(k -> source.equals(k.source()))
                .count();
        items.values().removeIf(k -> source.equals(k.source()));       // ① 这本旧的全不要
        for (KnowledgeItem item : newItems) items.put(item.id(), item); // ② 整份换成新的
        purgeOrphanVectors();
        persist();
        log.info("重灌来源 {}:清掉旧条目 {} 条,新收录 {} 条", source, removed, newItems.size());
        return newItems.size();
    }

    /** 清掉向量表里已经不存在对应条目的记录(删条目时顺手调用),免得 sidecar 越滚越大。 */
    private void purgeOrphanVectors() {
        vectors.keySet().removeIf(id -> !items.containsKey(id));
    }

    // ---------------------------------------------------------------- 检索(hybrid + 阈值)

    @Override
    public synchronized List<KnowledgeItem> search(String query, int topK) {
        if (query == null || query.isBlank() || items.isEmpty() || topK <= 0) return List.of();

        List<String> terms = tokenize(query);
        float[] queryVector = tryEmbed(query);
        boolean hasVector = queryVector != null;
        if (!hasVector && terms.isEmpty()) return List.of();

        int k = Math.min(topK, items.size());
        // 有界小顶堆:只保留当前最好的 k 条,O(N log K),不再把全库命中装进 List 再全排序。
        PriorityQueue<Scored> heap = new PriorityQueue<>(
                Comparator.comparingDouble(Scored::score)
                        .thenComparing(Scored::seq, Comparator.reverseOrder()));
        int seq = 0;
        for (KnowledgeItem item : items.values()) {
            float[] v = vectors.get(item.id());
            double vectorScore = (hasVector && v != null) ? Vectors.cosine(queryVector, v) : 0;
            double keywordScore = keywordScore(item, terms);

            double score = hasVector
                    ? VECTOR_WEIGHT * vectorScore + (1 - VECTOR_WEIGHT) * keywordScore
                    : keywordScore;
            if (score <= 0 || score < minScore) continue;       // 阈值:不够相关就当没找到
            Scored candidate = new Scored(item, score, seq++);
            if (heap.size() < k) {
                heap.add(candidate);
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.add(candidate);
            }
        }

        List<Scored> top = new ArrayList<>(heap);
        top.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingInt(Scored::seq));
        if (log.isDebugEnabled()) {
            top.forEach(s -> log.debug("检索命中 {}(score={}, 向量={})",
                    s.item().name(), String.format(Locale.ROOT, "%.3f", s.score()),
                    hasVector ? "开" : "关"));
        }
        return top.stream().map(Scored::item).toList();
    }

    @Override
    public int missingVectorCount() {
        int missing = 0;
        for (KnowledgeItem item : items.values()) {
            if (vectors.get(item.id()) == null) missing++;
        }
        return missing;
    }

    /**
     * 显式、分批、可恢复地补向量:每批算完立即写 sidecar,进程中断后再次调用会从剩余条目继续。
     * 查询路径不再调用它;embedding 服务没开时直接报错,由管理端决定何时重试。
     */
    @Override
    public synchronized VectorBackfillResult backfillVectors(int limit) {
        int total = items.size();
        List<KnowledgeItem> missing = items.values().stream()
                .filter(k -> vectors.get(k.id()) == null)
                .toList();
        if (missing.isEmpty()) return new VectorBackfillResult(0, 0, total, true);

        int batchSize = Math.max(1, Math.min(limit, missing.size()));
        List<KnowledgeItem> batch = missing.subList(0, batchSize);
        List<String> texts = batch.stream()
                .map(k -> k.name() + " " + (k.detail() == null ? "" : k.detail()))
                .toList();

        List<float[]> computed;
        try {
            computed = embedder.embedAll(texts);
        } catch (RuntimeException e) {
            throw new AgentException("向量补齐失败(embedding 不可用):" + e.getMessage(), e);
        }
        if (computed == null || computed.size() != batch.size()) {
            throw new AgentException("向量补齐失败:模型返回数量与请求不一致");
        }
        for (int i = 0; i < batch.size(); i++) {
            vectors.put(batch.get(i).id(), computed.get(i));
        }
        if (!persistVectors()) {
            throw new AgentException("向量补齐已计算,但 sidecar 落盘失败;下次会重试");
        }

        int remaining = missing.size() - batchSize;
        return new VectorBackfillResult(batchSize, remaining, total, remaining == 0);
    }

    private float[] tryEmbed(String text) {
        try {
            return embedder.embed(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 切词:按空白/标点;中文无空格时退化为整串(再由关键词命中率兜底)。 */
    private static List<String> tokenize(String query) {
        List<String> terms = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT)
                .split("[\\s,，。、;；:：!?！?()（）\\[\\]]+")) {
            if (!t.isBlank()) terms.add(t);
        }
        return terms;
    }

    /** 关键词命中率:命中的词数 / 总词数(0~1)。 */
    private static double keywordScore(KnowledgeItem item, List<String> terms) {
        if (terms.isEmpty()) return 0;
        String haystack = (item.name() + " " + item.detail()).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String t : terms) {
            if (haystack.contains(t)) hits++;
        }
        return (double) hits / terms.size();
    }

    // ---------------------------------------------------------------- 落盘

    private void persist() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(file)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, items.values());
            }
        } catch (IOException e) {
            throw new AgentException("写入知识库失败:" + file, e);
        }
    }

    /** 向量落盘失败不该影响普通写入;显式补向量流程会检查返回值并报错。 */
    private boolean persistVectors() {
        try {
            Path parent = vectorFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            int dim = vectors.values().stream().findFirst().map(v -> v.length).orElse(0);
            try (OutputStream out = Files.newOutputStream(vectorFile)) {
                mapper.writeValue(out, new VectorFile(embedder.modelName(), dim, vectors));
            }
            return true;
        } catch (IOException e) {
            log.warn("向量落盘失败(不影响本次运行):{}", e.getMessage());
            return false;
        }
    }
}
