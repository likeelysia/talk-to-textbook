package io.github.likeelysia.formalagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置:从 {@code application.yml} 的 {@code fa.*} 绑定过来(原 {@code @Value} 版的升级)。
 *
 * <p>好处:
 * <ol>
 *   <li><b>类型安全</b>:名字/类型写错,启动就报错,不用等运行期;</li>
 *   <li><b>分组</b>:deepseek / vision / embedding 各成一块,IDE 能补全、能跳转;</li>
 *   <li><b>松散绑定</b>:yml 里写 {@code max-tokens},Java 里叫 {@code maxTokens} 也能对上;</li>
 *   <li>支持 <b>profile</b> 与环境变量覆盖(见 application.yml 的 {@code ${FA_...:默认值}})。</li>
 * </ol>
 *
 * <p>注册方式见 {@link SpringConfig} 上的 {@code @EnableConfigurationProperties}
 * —— 这样普通 Spring 上下文(测试)也能用。
 */
@ConfigurationProperties(prefix = "fa")
public record AppConfig(
        boolean debug,
        Llm llm,
        Deepseek deepseek,
        Vision vision,
        Embedding embedding,
        Upload upload,
        Conversation conversation,
        Memory memory) {

    /** 调用 LLM 的通用参数(超时 / 重试)。 */
    public record Llm(int requestTimeoutSeconds, int connectTimeoutSeconds,
                      int maxAttempts, long retryBaseMs, long retryMaxMs) {
    }

    /** DeepSeek:文本对话 / 知识点提炼。 */
    public record Deepseek(String apiUrl, String model, int maxTokens) {
    }

    /** Kimi 视觉:扫描件 OCR / 图片问答。 */
    public record Vision(String apiUrl, String model, int maxTokens) {
    }

    /** 本地 embedding 服务。 */
    public record Embedding(String url, String model) {
    }

    /** 上传文件落盘目录(客户端上传的教材存这里)。 */
    public record Upload(String dir) {
    }

    /** 会话(对话框)落盘目录:一个会话一个 JSON + 一份 index.json。 */
    public record Conversation(String dir) {
    }

    /** 记忆:短期 = 会话内“最近几轮 + 滚动摘要”;长期 = 跨轮累积的事实 / 偏好。 */
    public record Memory(ShortTerm shortTerm, LongTerm longTerm) {

        /**
         * 短期记忆的窗口参数。
         *
         * @param maxTurns  最多带最近几轮原文(一个“轮”≈ 一条用户提问 + 它的回答);<=0 = 不按轮数限
         * @param maxChars  原文总字符预算(中文 1 字 ≈ 1~2 token,6000 字量级对上下文很轻松);<=0 = 不限
         * @param summarize 超出窗口的部分要不要压成滚动摘要(不压 = 直接丢,只留最近几轮)
         */
        public record ShortTerm(int maxTurns, int maxChars, boolean summarize) {
        }

        /**
         * 长期记忆(跨轮、跨窗口地记着“关于用户已知什么”)。
         *
         * @param enabled           开关;关掉 = 既不提炼也不召回
         * @param dir               记忆落盘目录(一个记忆域一个 JSON)
         * @param extractEveryTurns 攒够几轮提炼一次(1 轮 = 一条提问 + 它的回答)
         * @param keepScore         模型打分低于它就丢掉(0 = 全留)
         * @param recallTopK        每次提问最多召回几条
         * @param dedupSimilarity   两条记忆余弦相似度超过它就当成同一件事,合并
         * @param maxItems          一次提炼最多入库几条
         * @param minChars          一轮对话短于这么多字就不送去提炼(寒暄 / 确认)
         * @param maxStored         一个记忆域最多存几条(超了就淘汰"最久没被用到"的;<b>钉住的永不淘汰</b>);<=0 = 用默认值
         */
        public record LongTerm(boolean enabled, String dir, int extractEveryTurns, double keepScore,
                               int recallTopK, double dedupSimilarity, int maxItems, int minChars,
                               int maxStored) {
        }
    }
}
