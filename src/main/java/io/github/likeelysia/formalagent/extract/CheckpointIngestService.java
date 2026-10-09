package io.github.likeelysia.formalagent.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.doc.DocPipeline;
import io.github.likeelysia.formalagent.doc.TextChunk;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>带断点的教材摄取</b>:把一整本书按「块」灌 —— 每块默认 40 页。
 *
 * <p>和 {@link IngestService#ingest(Path, String, boolean)}(一把梭:整本读完 → 整本提炼 → 存一次)
 * 的区别只在<b>失败半径</b>:
 *
 * <pre>
 *   一把梭:  第 380 页限流挂了  →  前面 379 页白干,从头再来(一个多小时)
 *   带断点:  第 380 页限流挂了  →  重跑命令,从第 361 页接着灌(前 9 块不用重来)
 * </pre>
 *
 * <p>每块结束时做两件事,顺序固定:<br>
 * ① {@code store.addAll(本块条目)} → 知识库落盘(同 id 覆盖,重跑一块也安全,不会重复);<br>
 * ② 写断点文件 → 记录"已灌到第 N 页"。<br>
 * 万一在两者之间挂了,重跑会重做这一块 —— 因为第 ① 步幂等,结果一样。<b>反之先写断点再落库会丢数据,所以顺序不能反。</b>
 *
 * <p>块之间不会切坏内容:切块本来就是「按页」做的(一个块不会跨页),所以 40 页的边界很干净。
 */
@Component
public class CheckpointIngestService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointIngestService.class);

    private final DocPipeline pipeline;
    private final KnowledgeExtractor extractor;
    private final KnowledgeStore store;
    private final ObjectMapper mapper;
    private final Path checkpointDir;
    private final int blockSize;

    public CheckpointIngestService(DocPipeline pipeline,
                                   KnowledgeExtractor extractor,
                                   KnowledgeStore store,
                                   ObjectMapper mapper,
                                   @Value("${fa.checkpoint.dir:data/checkpoints}") String checkpointDir,
                                   @Value("${fa.extract.block-size:40}") int blockSize) {
        this.pipeline = pipeline;
        this.extractor = extractor;
        this.store = store;
        this.mapper = mapper;
        this.checkpointDir = Path.of(checkpointDir);
        this.blockSize = Math.max(1, blockSize);
    }

    /** 断点粒度(页)。 */
    public int blockSize() {
        return blockSize;
    }

    /** 默认:按文件名当来源,不重灌,不强制重来。 */
    public IngestProgress ingest(Path file) {
        return ingest(file, null, false, false);
    }

    /**
     * 灌一本书(带断点)。
     *
     * @param source  知识来源(书名);空 = 用文件名
     * @param replace {@code true} = 重灌:开跑前先清掉这个来源的旧条目(只在<b>从头开始</b>时做,
     *                续灌时不动,否则会把已经灌好的块也清掉)
     * @param restart {@code true} = 忽略已有断点,从第 1 页重来
     * @return 结束时的进度(冲完成 {@code donePages == totalPages})
     */
    public IngestProgress ingest(Path file, String source, boolean replace, boolean restart) {
        if (file == null) throw new AgentException("文件路径不能为空");
        if (!Files.isRegularFile(file)) throw new AgentException("文件不存在:" + file.toAbsolutePath());

        String sourceName = (source == null || source.isBlank())
                ? file.getFileName().toString()
                : source.strip();
        long fileSize = sizeOf(file);
        String fileName = file.getFileName().toString();
        int total = pipeline.pageCount(file);

        Path progressFile = checkpointDir.resolve(
                IngestProgress.keyOf(sourceName, fileName, fileSize) + ".json");
        IngestProgress progress = restart ? null : readProgress(progressFile);

        if (progress != null && (progress.fileSize() != fileSize || progress.totalPages() != total)) {
            log.warn("源文件换版本了(大小 {} → {} / 页数 {} → {}),旧断点作废,重新开始",
                    progress.fileSize(), fileSize, progress.totalPages(), total);
            progress = null;
        }

        if (progress != null && progress.completedCleanly()) {
            log.info("《{}》已经灌完({}/{} 页,共 {} 条,无漏页),跳过。要重灌请加 restart",
                    sourceName, progress.donePages(), total, progress.itemCount());
            return progress;
        }

        if (progress == null) {
            progress = IngestProgress.fresh(sourceName, fileName, fileSize, total, blockSize);
            if (replace) {
                int removed = store.removeBySource(sourceName);       // 从头灌才清旧
                log.info("重灌《{}》:先清掉旧条目 {} 条", sourceName, removed);
            }
            saveProgress(progressFile, progress);                     // 先落一张"0 页"的断点,便于查看
            log.info("开始灌《{}》:共 {} 页,每 {} 页一个断点(共 {} 块)",
                    sourceName, total, blockSize, (total + blockSize - 1) / blockSize);
        } else {
            log.info("从断点续灌《{}》:已完成 {}/{} 页,继续", sourceName, progress.donePages(), total);
        }

        progress = retryFailed(file, sourceName, progress, progressFile);   // ① 先把上轮漏的页补上

        for (int from = progress.donePages() + 1; from <= total; from += blockSize) {
            int to = Math.min(from + blockSize - 1, total);
            BlockResult block = runBlock(file, sourceName, from, to);

            store.addAll(block.items());                              // ① 整块落库(幂等)
            progress = progress.afterBlock(to, progress.itemCount() + block.items().size(),
                    progress.failedChunks() + block.failedChunks(),
                    mergePages(progress.failedPages(), block.failedPages()));
            saveProgress(progressFile, progress);                     // ② 再记断点
            log.info("[断点] 第 {}-{} 页完成:本块 {} 条 / 累计 {} 条 / 进度 {}/{} 页{}",
                    from, to, block.items().size(), progress.itemCount(), to, total,
                    block.failedPages().isEmpty() ? "" : ",本块漏页 " + block.failedPages());
        }
        if (!progress.failedPages().isEmpty()) {
            log.warn("《{}》页已跑完,但还有 {} 页始终没灌进去:{} —— 重跑同一条命令会自动补灌这几页",
                    sourceName, progress.failedPages().size(), progress.failedPages());
        }
        return progress;
    }

    /**
     * <b>补灌</b>:上一轮没读成功的页,单独一页一页重跑。
     *
     * <p>故意放在<b>开跑前</b>、而不是"整本跑完后",因为:① 补一页落一次盘 → 补灌本身可续;
     * ② 万一补灌时又挂,下次开跑会自动接着补;③ 不用等到整本跑完才处理(人可能早走了)。
     *
     * <p>补上了就从 {@code failedPages} 里划掉;补不上的留着 —— <b>不阻塞、不死循环</b>,
     * 只是每次开跑再试一次,并在 {@code --progress} / 日志里列出来。
     */
    private IngestProgress retryFailed(Path file, String sourceName, IngestProgress progress, Path progressFile) {
        List<Integer> pending = progress.failedPages();
        if (pending.isEmpty()) return progress;
        log.info("发现 {} 页上一轮没灌成功,先补灌:{}", pending.size(), pending);
        for (int page : pending) {
            try {
                BlockResult one = runBlock(file, sourceName, page, page);   // 单页块
                store.addAll(one.items());
                List<Integer> left = one.failedPages().contains(page)
                        ? progress.failedPages()                            // 还是没成 → 留着
                        : without(progress.failedPages(), page);
                progress = progress.afterBlock(progress.donePages(),
                        progress.itemCount() + one.items().size(),
                        progress.failedChunks() + one.failedChunks(), left);
                saveProgress(progressFile, progress);
                log.info("[补灌] 第 {} 页成功:本页 {} 条 / 没成功的页还剩 {}",
                        page, one.items().size(), left.size());
            } catch (AgentException e) {                                 // 整页颗粒无收 → 留到下次
                log.warn("[补灌] 第 {} 页又失败,留到下次:{}", page, e.getMessage());
            }
        }
        return progress;
    }

    /**
     * 跑一块:读 [from, to] 页 → 提炼 → 返回条目。整块颗粒无收就抛错(好让断点停在此块之前)。
     *
     * <p>顺手回收两类"漏页":① OCR 单页失败(读取器上报的页号)② 提炼失败的块所在的页
     * (从 chunk 出处「第 N 页」里读出来)。这些页号会进断点,下次开跑先补灌。
     */
    private BlockResult runBlock(Path file, String sourceName, int from, int to) {
        DocPipeline.LoadedDoc loaded = pipeline.loadReport(file, from, to);
        List<TextChunk> chunks = loaded.chunks();
        Map<String, KnowledgeItem> items = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        List<Integer> failedPages = new ArrayList<>(loaded.failedPages());
        for (TextChunk chunk : chunks) {
            List<KnowledgePoint> points;
            try {
                points = extractor.extract(chunk.text());
            } catch (AgentException e) {                              // 单块失败不拖垮整本
                failed.add(chunk.location());
                Integer page = pageOf(chunk.location());              // 「第 N 页」→ N
                if (page != null) failedPages.add(page);
                log.warn("提炼 {} 失败,已跳过:{}", chunk.location(), e.getMessage());
                continue;
            }
            for (KnowledgePoint p : points) {
                items.putIfAbsent(p.name(), IngestService.itemOf(sourceName, p, chunk.location()));
            }
        }
        if (chunks.isEmpty()) {
            // 一块文本都没有:这些页全是空白页 / 封面 / 篇扉页(OCR 读完了,只是没正文)。
            // 这是正常结果,按「0 条」返回 —— 别记进漏页清单,不然永远清不掉。
            if (!failedPages.isEmpty()) {                          // 例外:OCR 是明着的失败,那就是真漏页
                throw new AgentException("第 " + from + "-" + to + " 页没读到任何正文,且有 "
                        + failedPages.size() + " 页 OCR 失败:" + failedPages);
            }
            log.info("第 {}-{} 页没有任何正文(空白页/篇扉页),本块 0 条", from, to);
            return new BlockResult(List.of(), 0, List.of());
        }
        if (failed.size() == chunks.size()) {
            throw new AgentException("第 " + from + "-" + to + " 页一块都没成功(共 " + chunks.size()
                    + " 块,失败 " + failed.size() + " 块)。断点仍停在第 " + (from - 1)
                    + " 页,修好后重跑同一条命令即可续灌");
        }
        return new BlockResult(List.copyOf(items.values()), failed.size(), sortedDistinct(failedPages));
    }

    private record BlockResult(List<KnowledgeItem> items, int failedChunks, List<Integer> failedPages) {
    }

    // ---------------------------------------------------------------- 漏页清单的小工具

    /** 合并两份页号为一份<b>升序去重</b>清单。 */
    private static List<Integer> mergePages(List<Integer> a, List<Integer> b) {
        TreeSet<Integer> set = new TreeSet<>();
        if (a != null) set.addAll(a);
        if (b != null) set.addAll(b);
        return List.copyOf(set);
    }

    /** 升序去重(可能是重复报上来的页号)。 */
    private static List<Integer> sortedDistinct(List<Integer> pages) {
        return List.copyOf(new TreeSet<>(pages));
    }

    /** 从清单里去掉一页。 */
    private static List<Integer> without(List<Integer> pages, int page) {
        List<Integer> out = new ArrayList<>(pages);
        out.remove(Integer.valueOf(page));
        return List.copyOf(out);
    }

    /** 从出处「第 N 页」里读出页号;读不出(非 PDF 的出处没有页码)返回 null。 */
    private static Integer pageOf(String location) {
        if (location == null) return null;
        Matcher m = PAGE.matcher(location);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 出处里的页码格式,和 {@code PdfReader} 写的「第 N 页」保持一致。 */
    private static final Pattern PAGE = Pattern.compile("第\\s*(\\d+)\\s*页");

    // ---------------------------------------------------------------- 断点文件读写

    private IngestProgress readProgress(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), IngestProgress.class);
        } catch (IOException e) {
            log.warn("断点文件坏了({}),当作没灌过:{}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private void saveProgress(Path file, IngestProgress progress) {
        try {
            Files.createDirectories(checkpointDir);
            Files.writeString(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(progress),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException("写断点文件失败:" + file, e);
        }
    }

    /** 列出所有断点(给命令行 --progress 用)。 */
    public List<IngestProgress> allProgress() {
        if (!Files.isDirectory(checkpointDir)) return List.of();
        List<IngestProgress> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(checkpointDir, "*.json")) {
            for (Path f : ds) {
                IngestProgress p = readProgress(f);
                if (p != null) all.add(p);
            }
        } catch (IOException e) {
            throw new AgentException("读断点目录失败:" + checkpointDir, e);
        }
        all.sort(Comparator.comparing(IngestProgress::updatedAt).reversed());
        return all;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new AgentException("读文件大小失败:" + file, e);
        }
    }
}
