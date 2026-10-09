package io.github.likeelysia.formalagent.ocr;

import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.HardwareConfig;
import io.github.mymonstercat.ocr.config.ParamConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地 OCR 引擎:RapidOCR(onnx 推理,PaddleOCR 的 PP-OCRv4 中文模型,全 CPU 跑)。
 *
 * <p><b>为什么用它</b>:灌一本 406 页的扫描件,走远程视觉模型要 17 秒/页(≈2 小时)还撞组织级 RPM 限流;
 * 本地跑 1~3 秒/页,一二十钟头的事变成十几分钟,而且离线、零成本、不占配额。
 *
 * <p><b>代价</b>:公式、化学式、复杂表格这类版式是它的弱项(它擅长的是横排中文印刷体)。
 * 所以上层配了「结果体检 + 回退视觉模型」—— 读糊的页自动换 Kimi 重跑,见 {@link OcrEngines}。
 *
 * <p><b>加载时机</b>:native 库和模型(几百 MB)在<b>第一次调用时</b>才从 jar 里解出来加载,
 * 不会拖慢应用启动。而且 {@link InferenceEngine} 是静态单例 —— 一个 JVM 只有一份引擎,
 * 多重启几次也没关系(第二次拿到的还是同一个)。
 *
 * <p>它只在 {@code fa.ocr.engine=rapid} 时才会被创建 —— 用 kimi 的人不必把 onnx 那堆依赖硬拖到运行时
 * (手工 {@code java -cp} 跑 CLI 时尤其明显)。
 */
@Component
@ConditionalOnProperty(prefix = "fa.ocr", name = "engine", havingValue = "rapid")
public class RapidOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(RapidOcrEngine.class);

    /** CPU 线程数;<=0 表示按机器核数自动(库的默认取 cores/2) */
    private final int numThreads;

    /** 按长边缩放;0 = 不缩放(最清晰也最慢)。页图 200 DPI 已够用,一般不用调 */
    private final int maxSideLen;

    private volatile InferenceEngine engine;
    private volatile ParamConfig params;

    public RapidOcrEngine(@Value("${fa.ocr.rapid.num-threads:0}") int numThreads,
                          @Value("${fa.ocr.rapid.max-side-len:0}") int maxSideLen) {
        this.numThreads = numThreads;
        this.maxSideLen = maxSideLen;
    }

    @Override
    public String name() {
        return "rapid";
    }

    @Override
    public String ocr(byte[] image) {
        if (image == null || image.length == 0) {
            throw new AgentException("本地 OCR 收到空图片");
        }
        Path tmp = null;
        try {
            // ⚠️ 0.0.7 版 API 只收文件路径(带 byte[] 入参的 OcrInput 还没发布,只在主分支上),
            //    所以这里得先落一个临时图。落盘的是 JPEG 字节,很轻,用完即删。
            tmp = Files.createTempFile("ocr-rapid-", ".jpg");
            Files.write(tmp, image);
            OcrResult result = engine().runOcr(tmp.toString(), params());
            String text = (result == null) ? null : result.getStrRes();
            return text == null ? "" : text.strip();
        } catch (AgentException e) {
            throw e;
        } catch (IOException e) {
            throw new AgentException("本地 OCR 写临时图失败:" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // native 库加载失败 / 图片解码失败 / 模型崩了 —— 都归成一个异常,让上层决定要不要兜底
            throw new AgentException("本地 OCR 失败:" + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
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

    /** 懒加载:第一次调用时才解压 native 库 + 模型并初始化引擎 */
    private InferenceEngine engine() {
        InferenceEngine local = engine;
        if (local == null) {
            synchronized (this) {
                local = engine;
                if (local == null) {
                    HardwareConfig hardware = HardwareConfig.getOnnxConfig();   // CPU:gpuIndex = -1
                    if (numThreads > 0) hardware.setNumThread(numThreads);
                    log.info("[OCR] 初始化本地引擎 RapidOCR(PP-OCRv4,{} 线程)...", hardware.getNumThread());
                    long start = System.currentTimeMillis();
                    local = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4, hardware);
                    log.info("[OCR] 本地引擎就绪,耗时 {} ms", System.currentTimeMillis() - start);
                    engine = local;
                }
            }
        }
        return local;
    }

    private ParamConfig params() {
        ParamConfig local = params;
        if (local == null) {
            ParamConfig p = ParamConfig.getDefaultConfig();
            p.setMaxSideLen(maxSideLen);
            local = p;
            params = local;
        }
        return local;
    }
}
