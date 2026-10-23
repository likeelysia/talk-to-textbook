# syntax=docker/dockerfile:1
# ============================================================================
#  Talk to Textbook —— 可重复构建镜像
#  构建阶段用 Maven + JDK17;运行阶段只带 JRE,不把源码/测试/Windows 路径带进镜像。
#  知识库、词典、会话/记忆/笔记都通过 volume 挂载,不打进镜像。
# ============================================================================

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只复制 pom,利用 Docker 层缓存把依赖下载和源码变更分开
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre

# 健康检查用 curl;顺手建一个非 root 用户
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home /app app

WORKDIR /app
COPY --from=build /build/target/talk-to-textbook-*.jar /app/app.jar

# 运行数据目录由 compose 挂载;先建好并交给 app 用户
RUN mkdir -p /app/data /app/knowledge /app/sessions /app/logs /app/dict \
    && chown -R app:app /app

USER app

ENV TZ=Asia/Shanghai \
    FILE_ENCODING=UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 公开健康检查;它不暴露学习数据
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
