package io.github.likeelysia.formalagent.ocr;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.VisionClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kimi 视觉 OCR:把页图发给多模态模型,让它「照抄」图上的字。
 *
 * <p>它就是从 {@code PdfReader} 里搬出来的那段逻辑,行为基本不变(提示词还是 {@code prompts/pdf-ocr.txt}、
 * 调用失败照样抛异常交给上层记漏页),只是现在归 {@link OcrEngine} 管,能被选择、能被当兜底。
 *
 * <p><b>读出来是空的时候返回空串,不抛异常</b> —— 封面/空白页/只剩扫描水印的篇扉页本来就该是空的。
 * 到底是「白纸」还是「有东西没读出来」,由上层拿页图判断(见 {@code PdfReader#looksBlank})。
 *
 * <p><b>页间停顿</b>也一并搬到这里 —— 因为「分钟级速率限制」是 Kimi 自己的属性,
 * 谁调用它谁付这个代价,不该让调用方去猜。
 * 语义是「**两次调用的开始时刻至少相隔 `page-gap-ms`**」(不是「调完再睡固定时长」):
 * 这样无论这一页读得快还是慢,节拍都不会失控 —— 躲组织级 RPM 限流靠的就是「间隔」,不是「睡眠」。
 * 默认 1500ms 只够躲「组织并发 = 1」;撞 429(组织级 3 RPM)时把它调成 ≥ 21000 即可(60s 内最多 3 次)。
 */
@Component
public class KimiOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(KimiOcrEngine.class);

    private final VisionClient vision;

    /** 页间最小间隔(毫秒) */
    private final long pageGapMs;

    /** 上一次调用的开始时刻;0 = 还没调用过 */
    private final AtomicLong lastStart = new AtomicLong(0);

    public KimiOcrEngine(VisionClient vision,
                         @Value("${fa.ocr.kimi.page-gap-ms:1500}") long pageGapMs) {
        this.vision = vision;
        this.pageGapMs = pageGapMs;
    }

    @Override
    public String name() {
        return "kimi";
    }

    @Override
    public String ocr(byte[] image) {
        pace();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ocr-kimi-", ".jpg");
            Files.write(tmp, image);
            String text = vision.ask(tmp, Prompts.get("pdf-ocr"));
            // 空结果 = 这页没字(白纸/水印页) —— 正常返回空串,交给上层看着页图定夺;
            // 真正读失败会以异常形式从 vision.ask 抛出来。
            return text == null ? "" : text;
        } catch (IOException e) {
            throw new AgentException("Kimi OCR 写临时图失败:" + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** 页间节拍:保证「本次调用开始」距「上次调用开始」至少 {@link #pageGapMs} 毫秒 */
    private void pace() {
        long now = System.currentTimeMillis();
        long previous = lastStart.getAndSet(now);
        if (previous == 0) return;                       // 第一次不用等
        long wait = pageGapMs - (now - previous);        // 上一次读得慢时,这一页就不用再等
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时图删除失败(无所谓):{}", path, e);
        }
    }
}
