# PNAS Server 镜像:多阶段构建(Maven 构建 + JRE 运行时,内置 ffmpeg 供转码)
# 构建上下文:backend/(见 deploy/docker-compose.yml)

# --- 构建阶段 ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷贝 POM,利用缓存
COPY pom.xml .
COPY pnas-common/pom.xml pnas-common/
COPY pnas-server/pom.xml pnas-server/
RUN mvn -B -q dependency:go-offline || true

# 拷贝源码并打包
COPY pnas-common/src pnas-common/src
COPY pnas-server/src pnas-server/src
RUN mvn -B package -DskipTests

# --- 运行阶段 ---
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/pnas-server/target/pnas-server-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
