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
 * 视觉模型 OCR:把页图发给多模态模型,让它「照抄」图上的字。
 *
 * <p>它就是原来那段逻辑,行为不变(提示词还是 {@code prompts/pdf-ocr.txt}、
 * 调用失败照样抛异常交给上层记漏页),只是名字从「Kimi OCR」改成「视觉 OCR」——
 * 因为具体是哪家的模型由 {@code fa.vision.*} 配置决定,这个类不该知道。
 * <b>引擎标识 {@code vision} 保留旧名 {@code kimi} 作为别名</b>(老配置 {@code FA_OCR_ENGINE=kimi} 照样认)。
 *
 * <p><b>读出来是空的时候返回空串,不抛异常</b> —— 封面/空白页/只剩扫描水印的篇扉页本来就该是空的。
 * 到底是「白纸」还是「有东西没读出来」,由上层拿页图判断(见 {@code PdfReader#looksBlank})。
 *
 * <p><b>页间停顿</b>也归它管 —— 因为「分钟级速率限制」是<b>模型服务商</b>的属性,谁调用它谁付这个代价。
 * 语义是「**两次调用的开始时刻至少相隔 `page-gap-ms`**」(不是「调完再睡固定时长」):
 * 这样无论这一页读得快还是慢,节拍都不会失控 —— 躲组织级 RPM 限流靠的就是「间隔」,不是「睡眠」。
 * 默认 1500ms 只够躲「组织并发 = 1」;撞 429(组织级 3 RPM)时把它调成 ≥ 21000 即可(60s 内最多 3 次)。
 */
@Component
public class VisionOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrEngine.class);

    /** 引擎标识:新名 = {@code vision}(旧的 {@code kimi} 由 {@link OcrEngines} 做别名兼容) */
    public static final String NAME = "vision";

    private final VisionClient vision;

    /** 页间最小间隔(毫秒) */
    private final long pageGapMs;

    /** 上一次调用的开始时刻;0 = 还没调用过 */
    private final AtomicLong lastStart = new AtomicLong(0);

    public VisionOcrEngine(VisionClient vision,
                           @Value("${fa.ocr.vision.page-gap-ms:${fa.ocr.kimi.page-gap-ms:1500}}") long pageGapMs) {
        this.vision = vision;
        this.pageGapMs = pageGapMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String ocr(byte[] image) {
        pace();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ocr-vision-", ".jpg");
            Files.write(tmp, image);
            String text = vision.ask(tmp, Prompts.get("pdf-ocr"));
            // 空结果 = 这页没字(白纸/水印页) —— 正常返回空串,交给上层看着页图定夺;
            // 真正读失败会以异常形式从 vision.ask 抛出来。
            return text == null ? "" : text;
        } catch (IOException e) {
            throw new AgentException("视觉 OCR 写临时图失败:" + e.getMessage(), e);
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
