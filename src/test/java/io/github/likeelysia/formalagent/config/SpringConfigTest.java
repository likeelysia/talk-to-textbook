package io.github.likeelysia.formalagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.ChatSession;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.llm.VisionClient;
import io.github.likeelysia.formalagent.service.ChatService;
import io.github.likeelysia.formalagent.store.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 装配的「冒烟测试」:容器能起来、关键 bean 齐备、配置能绑定。
 *
 * <p>为什么要它:单元测试是各自 new 对象,<b>测不到"容器接线"</b>。
 * 一旦构造函数改了却忘了让容器注入,只有真启动一次容器才会暴露 —— 这个测试就是干这个的。
 *
 * <p>升级:从手写 {@code AnnotationConfigApplicationContext} 改成 {@code @SpringBootTest}
 * —— 这样才会真正加载 {@code application.yml}(手动上下文读不到 Boot 的配置文件)。
 * 用 {@code properties} 把交互式 CLI 关掉,免得它来抢 stdin。
 */
@SpringBootTest(properties = "fa.cli.enabled=false")
class SpringConfigTest {

    @Autowired ObjectMapper mapper;
    @Autowired SessionStore sessionStore;
    @Autowired ChatService chatService;
    @Autowired ChatSession chatSession;
    @Autowired LlmClient llmClient;
    @Autowired VisionClient visionClient;
    @Autowired AppConfig appConfig;

    @Test
    @DisplayName("容器能启动,关键 bean 齐备")
    void contextWiresUp() {
        assertNotNull(mapper, "ObjectMapper bean 应存在");
        assertNotNull(sessionStore, "SessionStore bean 应存在");
        assertNotNull(chatService, "ChatService bean 应存在");
        assertNotNull(chatSession, "ChatSession bean 应存在");
        assertNotNull(llmClient, "LlmClient 实现应存在");
        assertNotNull(visionClient, "VisionClient 实现应存在");
    }

    @Test
    @DisplayName("配置绑定:application.yml 的 fa.* 真的绑进 AppConfig 了")
    void bindsConfiguration() {
        assertEquals("deepseek-chat", appConfig.deepseek().model());
        assertEquals(5, appConfig.llm().maxAttempts());
        assertEquals(120, appConfig.llm().requestTimeoutSeconds());
        assertNotNull(appConfig.embedding().url(), "embedding.url 应有值");
        assertNotNull(appConfig.vision().apiUrl(), "vision.api-url 应有值");
        assertNotNull(appConfig.vision().model(), "vision.model 应有值");
    }
}
