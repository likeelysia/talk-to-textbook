package io.github.likeelysia.formalagent.doc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 文档管道:按扩展名选读取器 → 读成"带出处的文本段" → 切成"带出处的块"。 */
@Component
public class DocPipeline {

    private final ReaderRegistry registry;
    private final TextSplitter splitter;

    public DocPipeline(ReaderRegistry registry, TextSplitter splitter) {
        this.registry = registry;
        this.splitter = splitter;
    }

    /** 读文件 → 切块 → 返回带出处的块列表。 */
    public List<TextChunk> load(Path file) {
        return load(file, 1, Integer.MAX_VALUE);
    }

    /**
     * 只读 [fromPage, toPage] 页 → 切块(从 1 开始,左闭右闭)。
     *
     * <p>灌库断点用的就是它:一块 40 页,读一块、提炼一块、落一次盘。
     * 块与块之间天然不重叠 —— 切块本来就是"按页"进行的(一块的文本不会跨页)。
     */
    public List<TextChunk> load(Path file, int fromPage, int toPage) {
        return loadReport(file, fromPage, toPage).chunks();
    }

    /**
     * 只读 [fromPage, toPage] 页 → 切块,<b>并把没读成功的页号也带回来</b>。
     *
     * <p>和 {@link #load(Path, int, int)} 的区别就是多带一份"OCR 失败页号":
     * 灌库那边靠它记进断点、之后再补灌,不会静默丢页。
     */
    public LoadedDoc loadReport(Path file, int fromPage, int toPage) {
        ReadResult read = registry.forFile(file).readReport(file, fromPage, toPage);
        List<TextChunk> chunks = new ArrayList<>();
        for (TextSegment segment : read.segments()) {
            for (String piece : splitter.split(segment.text())) {
                chunks.add(new TextChunk(piece, segment.location()));
            }
        }
        return new LoadedDoc(chunks, read.failedPages());
    }

    /** 一块的读取结果:切好的文本块 + 没读成功的页号(空表 = 全都读到了)。 */
    public record LoadedDoc(List<TextChunk> chunks, List<Integer> failedPages) {
        public LoadedDoc {
            chunks = (chunks == null) ? List.of() : List.copyOf(chunks);
            failedPages = (failedPages == null) ? List.of() : List.copyOf(failedPages);
        }
    }

    /** 这个文件有多少页(决定切成几个断点)。 */
    public int pageCount(Path file) {
        return registry.forFile(file).pageCount(file);
    }
}