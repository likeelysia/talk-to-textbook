package io.github.likeelysia.formalagent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 配置:把"管理接口鉴权"挂到需要保护的路径上。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminKeyInterceptor adminKeyInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final ExpectedUserInterceptor expectedUserInterceptor;

    public WebConfig(AdminKeyInterceptor adminKeyInterceptor,
                     RateLimitInterceptor rateLimitInterceptor,
                     ExpectedUserInterceptor expectedUserInterceptor) {
        this.adminKeyInterceptor = adminKeyInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.expectedUserInterceptor = expectedUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 私有写请求：预期身份与实际登录身份不一致时，在 Controller 前拒绝
        registry.addInterceptor(expectedUserInterceptor).addPathPatterns("/api/**");
        // 入库 / 删库是"改知识库"的管理动作,必须鉴权;
        // 问答、概况接口登录后可用
        registry.addInterceptor(adminKeyInterceptor)
                .addPathPatterns("/api/ingest", "/api/knowledge/source",
                        "/api/knowledge/vectors/**");
        // 收费模型入口:每个会话/IP 每分钟限流
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/agent", "/api/agent/stream", "/api/vocab/explain");
    }
}
