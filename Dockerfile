# =============================================================================
# Enterprise Connector Dockerfile (Phase 5.6)
# 多阶段构建:
#   Stage 1 (build): 用 Maven + JDK 21 编译打包
#   Stage 2 (run)  : 只带 JRE 21 的 slim 镜像, 镜像体积约 250 MB
#
# 构建:
#   docker build -t enterprise-connector:latest .
#
# 运行 (参考 docker-compose.yml 里的环境变量配置):
#   docker run --rm -p 8089:8089 \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     -e DB_URL=jdbc:postgresql://host.docker.internal:5432/sea_star_ai \
#     -e DB_USERNAME=postgres -e DB_PASSWORD=xxx \
#     -e REDIS_HOST=host.docker.internal -e REDIS_PASSWORD= \
#     -e MCP_AUTH_TOKEN=xxx -e ADMIN_API_KEY=xxx \
#     -e ENCRYPTION_KEY=$(openssl rand -base64 32) \
#     -e CALLBACK_INBOUND_SECRET=xxx \
#     enterprise-connector:latest
# =============================================================================

# ----- Stage 1: build -----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先拷贝 pom 预热依赖缓存 (源码变化时不会重复下载 jar)
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw mvnw.cmd ./
RUN ./mvnw -q -B dependency:go-offline

# 拷贝源码编译打包 (跳过测试 — CI 另行跑单测/集测)
COPY src ./src
RUN ./mvnw -q -B -DskipTests package

# ----- Stage 2: runtime -----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 非 root 用户运行, 最小化被利用时的攻击面
RUN useradd --system --uid 1001 --home /app --shell /usr/sbin/nologin connector \
  && chown -R connector:connector /app
USER connector

# Spring Boot fat jar
COPY --from=build /workspace/target/*.jar app.jar

# JVM 默认参数 (容器感知), 生产按需在 docker-compose / K8s 里覆盖
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+HeapDumpOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod

# 生产 profile 默认暴露 8080 (application-prod.yaml: server.port=${SERVER_PORT:8080})
EXPOSE 8080

# 让 SIGTERM 触发 Spring Boot graceful shutdown (JVM 进程就是 PID 1, 会收到信号)
STOPSIGNAL SIGTERM

# 健康检查走 /health/liveness (HealthController 提供)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O- http://localhost:${SERVER_PORT:-8080}/health/liveness || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
