package io.github.likeelysia.formalagent.config;

import io.github.likeelysia.formalagent.exception.AgentException;

/**
 * API 密钥解析:把「密钥从哪来」这一件事,收在一个地方回答。
 *
 * <p>为什么值得单独一个类:项目要对接两个外部服务(DeepSeek 文本 / 视觉模型),各自都要一把 key,
 * 而取法完全一样 ——「配置里直接写」优先,否则读某个环境变量。以前这段逻辑散在各自的客户端里,
 * 而且<b>把环境变量名写死</b>({@code System.getenv("MOONSHOT_API_KEY")})→ 换厂商就得改代码。
 * 现在环境变量名本身也是配置({@code fa.*.api-key-env}),换厂商只改 yml。
 *
 * <p>优先级:{@code apiKey}(配置里直接给的值)&gt; 环境变量 {@code apiKeyEnv}。
 * 两者都没有 → 抛 {@link AgentException},并且是在<b>真正发请求之前</b>抛 ——
 * 免得用户传完图片、等半天,才看到「没配 key」。
 */
public final class ApiKeys {

    private ApiKeys() {
    }

    /** 解析密钥;拿不到就抛(错误信息里带上该配哪个变量,不用去翻源码)。 */
    public static String resolve(String directValue, String envName) {
        String key = find(directValue, envName);
        if (key == null) {
            throw new AgentException("没读到 API 密钥 —— 请设置环境变量 ["
                    + (envName == null || envName.isBlank() ? "(未配置 api-key-env)" : envName)
                    + "],或在配置里直接填 api-key");
        }
        return key;
    }

    /** 有没有密钥(不抛异常;给健康检查用 —— 它只想知道「配没配」)。 */
    public static boolean has(String directValue, String envName) {
        return find(directValue, envName) != null;
    }

    private static String find(String directValue, String envName) {
        if (directValue != null && !directValue.isBlank()) {
            return directValue.strip();
        }
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String value = System.getenv(envName);
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
