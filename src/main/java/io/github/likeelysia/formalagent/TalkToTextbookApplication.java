package io.github.likeelysia.formalagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口:Spring Boot 启动类。

 * <p>{@code @SpringBootApplication} = {@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan} —— 自动扫描本包及子包里的所有 {@code @Component}/{@code @Configuration}
 * (包括 {@link io.github.likeelysia.formalagent.config.SpringConfig} 里的 {@code @EnableRetry} 与
 * 手写 {@code @Bean})。
 *
 * <p>启动后干什么由容器里的 {@code CommandLineRunner} 决定(当前是 {@code cli.ChatCli} 的交互式命令行)。
 */
@SpringBootApplication
public class TalkToTextbookApplication {

    public static void main(String[] args) {
        SpringApplication.run(TalkToTextbookApplication.class, args);
    }
}
