package com.pnas.agent.remote;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** 带 Bearer 令牌的 JSON HTTP 客户端;非 2xx 抛 {@link AgentException}。 */
public final class HttpJson {

    private static final ObjectMapper MAPPER = buildMapper();

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String token;
    private int maxRetries;
    private long retryBackoffMs;

    public HttpJson(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    /**
     * 配置重试策略:最多重试 {@code maxRetries} 次(不含首次尝试),
     * 每次退避 {@code backoffMs * 2^n}(n 为已重试次数)。
     */
    public HttpJson withRetry(int maxRetries, long backoffMs) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负: " + maxRetries);
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs 不能为负: " + backoffMs);
        }
        this.maxRetries = maxRetries;
        this.retryBackoffMs = backoffMs;
        return this;
    }

    /** 可重试的远程操作。 */
    @FunctionalInterface
    public interface Action<T> {
        T run() throws IOException, InterruptedException;
    }

    /**
     * 按 {@link #withRetry(int, long)} 的策略执行一次操作。
     * 仅重试 {@link IOException} 与 5xx/429 的 {@link AgentException};
     * 4xx(含 401/403/409)绝不重试。中断立即恢复中断标记并向上抛出。
     */
    public <T> T executeWithRetry(Action<T> action) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
            } catch (AgentException e) {
                if (!retryable(e) || attempt >= maxRetries) {
                    throw e;
                }
            }
            backoff(attempt);
        }
    }

    /** 5xx 与 429 属于可重试的服务端临时故障。 */
    public static boolean retryable(AgentException e) {
        int status = e.status();
        return status == 429 || (status >= 500 && status <= 599);
    }

    private void backoff(int attempt) throws InterruptedException {
        long delay = retryBackoffMs;
        for (int i = 0; i < attempt && delay > 0 && delay < Long.MAX_VALUE / 2; i++) {
            delay *= 2;
        }
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public <T> T get(String path, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type, path);
    }

    public <T> T get(String path, TypeReference<T> type) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(res, path);
        if (res.body().length == 0) {
            return null;
        }
        return MAPPER.readValue(res.body(), type);
    }

    public <T> T post(String path, Object body, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body))).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type, path);
    }

    public <T> T patch(String path, Object body, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body)))
            .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type, path);
    }

    public byte[] getBytes(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(res, path);
        return res.body();
    }

    public void putBytes(String path, byte[] body, String sha256) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = client.send(request(path)
            .header("Content-Type", "application/octet-stream")
            .header("X-Sha256", sha256)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(res, path);
    }

    /** 带重试的 POST(仅用于幂等/可安全重放的上传完成等调用)。 */
    public <T> T postWithRetry(String path, Object body, Class<T> type)
            throws IOException, InterruptedException {
        return executeWithRetry(() -> post(path, body, type));
    }

    /** 带重试的分块 PUT。 */
    public void putBytesWithRetry(String path, byte[] body, String sha256)
            throws IOException, InterruptedException {
        executeWithRetry(() -> {
            putBytes(path, body, sha256);
            return null;
        });
    }

    public ObjectMapper mapper() {
        return MAPPER;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    }

    private static void requireSuccess(HttpResponse<byte[]> res, String path) {
        if (res.statusCode() / 100 != 2) {
            throw new AgentException(res.statusCode(), "HTTP " + res.statusCode() + " for " + path
                + ": " + new String(res.body(), StandardCharsets.UTF_8));
        }
    }

    private <T> T handle(HttpResponse<byte[]> res, Class<T> type, String path) throws IOException {
        requireSuccess(res, path);
        if (type == Void.class || res.body().length == 0) {
            return null;
        }
        return MAPPER.readValue(res.body(), type);
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                if (p.currentToken() == JsonToken.VALUE_NULL) {
                    return null;
                }
                if (p.currentToken().isNumeric()) {
                    return Instant.ofEpochMilli(p.getLongValue());
                }
                String text = p.getText();
                return text == null || text.isBlank() ? null : Instant.parse(text);
            }
        });
        mapper.registerModule(module);
        return mapper;
    }
}
