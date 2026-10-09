package io.github.likeelysia.formalagent.doc;

import java.nio.file.Path;
import java.util.List;

/** 文档读取器:给我一个文件路径,还我一串"带出处的文本段"。 */
public interface DocumentReader {

    List<TextSegment> read(Path file);

    /**
     * 带页码范围地读(从 1 开始,左闭右闭)。
     *
     * <p>默认实现忽略范围 —— txt / md / 单张图片本来就没有"页"的概念。
     * 大 PDF 必须覆盖它:灌库断点靠它"一次只读 40 页",而不是把 400 页整本重来一遍。
     */
    default List<TextSegment> read(Path file, int fromPage, int toPage) {
        return read(file);
    }

    /**
     * 带页码范围地读,并且<b>把没读成功的页号也带回来</b>。
     *
     * <p>默认实现 = 调 {@link #read(Path, int, int)},报告"没有失败页"。
     * txt / md / 图片没有 OCR 概念,不必覆盖;只有"逐页 OCR 可能单页失败"的 PDF 需要覆盖,
     * 好让灌库断点把漏掉的页记下来、之后再补灌。
     */
    default ReadResult readReport(Path file, int fromPage, int toPage) {
        return new ReadResult(read(file, fromPage, toPage), List.of());
    }

    /** 这个文件有多少"页"(没有页概念的格式返回 1)。断点怎么切由它决定。 */
    default int pageCount(Path file) {
        return 1;
    }
}