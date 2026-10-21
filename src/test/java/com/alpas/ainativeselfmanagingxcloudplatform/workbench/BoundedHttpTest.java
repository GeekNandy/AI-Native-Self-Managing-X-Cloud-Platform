package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

class BoundedHttpTest {
    @Test
    void completeResponseDeadlineIncludesAStalledBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(new byte[] {1});
                    exchange.getResponseBody().flush();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/"))
                            .build();
            long start = System.nanoTime();
            assertThrows(
                    HttpTimeoutException.class,
                    () -> BoundedHttp.send(http, request, 1000, Duration.ofMillis(150)));
            assertTrue(
                    (System.nanoTime() - start) / 1_000_000 < 1500,
                    "Body read ignored the complete-response deadline");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void oversizedResponseCancelsBeforeBufferingTheWholeBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(200, 2048);
                        exchange.getResponseBody().write(new byte[2048]);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/"))
                            .build();
            assertThrows(
                    BoundedHttp.ResponseTooLarge.class,
                    () -> BoundedHttp.send(http, request, 1024, Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }
}
