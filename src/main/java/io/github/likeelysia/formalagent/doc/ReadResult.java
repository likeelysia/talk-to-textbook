package io.github.likeelysia.formalagent.doc;

import java.util.List;

/**
 * 读取一段页面的结果:<b>读到的文本段</b> + <b>没读成功的页号</b>。
 *
 * <p>为什么要专门带出"没读成功的页号":扫描件走视觉 OCR 时,单页失败(限流 429 / 图太糊)
 * 会被跳过 —— 否则一页打嗝就得整本重来。但"跳过"等于<b>静默丢数据</b>:这一页上的知识点
 * 永远进不了库,而且断点还以为这页"灌过了"。
 *
 * <p>所以把页号一路带出去,交给灌库断点记下来,之后再补灌。页号是<b>物理页序</b>(和知识条目
 * 的 location「第 N 页」完全一致)。
 *
 * @param segments    读到的文本段(失败页不在其中)
 * @param failedPages 没读成功的页号(1 起,升序去重;没有失败就是空表)
 */
public record ReadResult(List<TextSegment> segments, List<Integer> failedPages) {

    public ReadResult {
        segments = (segments == null) ? List.of() : List.copyOf(segments);
        failedPages = (failedPages == null) ? List.of() : List.copyOf(failedPages);
    }

    /** 空结果:没读到东西,也没有失败页(比如页码范围本身就是空的)。 */
    public static ReadResult empty() {
        return new ReadResult(List.of(), List.of());
    }
}
