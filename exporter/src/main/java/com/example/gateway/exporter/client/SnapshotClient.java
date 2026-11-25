package com.example.gateway.exporter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 拉取网关只读快照，并校验 schema。 */
public final class SnapshotClient implements SnapshotSource {

    private final HttpClient http;
    private final URI uri;
    private final String expectedSchema;
    private final int defaultTimeoutMs;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public SnapshotClient(URI uri, String expectedSchema, int timeoutMs) {
        this.uri = uri;
        this.expectedSchema = expectedSchema;
        this.defaultTimeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public JsonNode fetch(long budgetMs) throws IOException, InterruptedException {
        int timeout = (int) Math.max(200, Math.min(budgetMs, defaultTimeoutMs));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeout))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("上游返回 HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        String schema = root.path("schema").asText("");
        if (!expectedSchema.equals(schema)) {
            throw new IOException("schema 不匹配：期望 " + expectedSchema + "，实际 " + schema);
        }
        return root;
    }
}
