package com.example.gateway.exporter.web;

import com.example.gateway.exporter.collect.CgwCollector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsHandlerTest {

    private HttpServer server;
    private URI metricsUri;

    @BeforeEach
    void setUp() throws Exception {
        CgwCollector collector = new CgwCollector(budget -> com.fasterxml.jackson.databind.json.JsonMapper
                .builder().build().readTree("{\"schema\":\"cgw.exporter.snapshot/v1\","
                        + "\"generated_at_ms\":1,\"gateway\":{\"ready\":true},"
                        + "\"nodes\":[],\"jobs\":[],\"counters\":{},\"histograms\":{}}"), Map.of());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/metrics", new MetricsHandler(collector, 3000));
        server.createContext("/", new LandingPage("http://127.0.0.1:1/snapshot", "test"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        metricsUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/metrics");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void servesMetricsAndLandingPage() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> metrics = client.send(
                HttpRequest.newBuilder(metricsUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.headers().firstValue("Content-Type").orElse("").contains("text/plain"));
        assertTrue(metrics.body().contains("cgw_gateway_up"));

        HttpResponse<String> landing = client.send(
                HttpRequest.newBuilder(metricsUri.resolve("/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, landing.statusCode());
        assertTrue(landing.body().contains("cgw-exporter"));
    }

    @Test
    void handlesConcurrentScrapes() {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(metricsUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::statusCode));
        }
        for (CompletableFuture<Integer> f : futures) {
            assertEquals(200, f.join());
        }
    }
}
