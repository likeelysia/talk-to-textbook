package io.github.likeelysia.formalagent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 配置:把"管理接口鉴权"挂到需要保护的路径上。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminKeyInterceptor adminKeyInterceptor;

    public WebConfig(AdminKeyInterceptor adminKeyInterceptor) {
        this.adminKeyInterceptor = adminKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 入库 / 删库是"改知识库"的管理动作,必须鉴权;
        // 问答、概况接口是对外开放的,不拦(否则普通用户用不了)
        registry.addInterceptor(adminKeyInterceptor)
                .addPathPatterns("/api/ingest", "/api/knowledge/source");
    }
}
