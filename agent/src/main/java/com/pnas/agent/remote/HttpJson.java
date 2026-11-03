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

    public HttpJson(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
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
