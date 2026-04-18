package com.acp.cw3.service;

import com.acp.cw3.model.MarketOdds;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketClientTest {

    @Test
    void mapsMarketToStateOddsFromYesPrice() throws Exception {
        String payload = """
                [
                  {
                    "question":"Will Republican win Texas Senate race?",
                    "yesPrice":0.62
                  }
                ]
                """;
        try (TestServer server = new TestServer(payload)) {
            PolymarketClient client = new PolymarketClient(
                    RestClient.create(),
                    true,
                    server.url(),
                    "",
                    "TX=Texas Senate"
            );
            Map<String, MarketOdds> odds = client.fetchOddsByState();
            assertTrue(odds.containsKey("TX"));
            assertEquals(0.62, odds.get("TX").repProbability(), 0.0001);
            assertEquals(0.38, odds.get("TX").demProbability(), 0.0001);
        }
    }

    @Test
    void returnsEmptyWhenApiUnavailable() {
        PolymarketClient client = new PolymarketClient(
                RestClient.create(),
                true,
                "http://127.0.0.1:9/unavailable",
                "",
                "TX=Texas Senate"
        );
        assertTrue(client.fetchOddsByState().isEmpty());
    }

    @Test
    void parsesEventSlugPayloadWithStringOutcomePrices() throws Exception {
        String payload = """
                {
                  "markets": [
                    {
                      "active": true,
                      "question": "Will the Democrats win the North Carolina Senate race in 2026?",
                      "outcomes": "[\\"Yes\\", \\"No\\"]",
                      "outcomePrices": "[\\"0.84\\", \\"0.16\\"]"
                    },
                    {
                      "active": true,
                      "question": "Will the Republicans win the North Carolina Senate race in 2026?",
                      "outcomes": "[\\"Yes\\", \\"No\\"]",
                      "outcomePrices": "[\\"0.14\\", \\"0.86\\"]"
                    }
                  ]
                }
                """;
        try (TestServer server = new TestServer(payload)) {
            PolymarketClient client = new PolymarketClient(
                    RestClient.create(),
                    true,
                    server.url().endsWith("/") ? server.url().substring(0, server.url().length() - 1) : server.url(),
                    "NC=north-carolina-senate-election-winner",
                    "NC=North Carolina Senate"
            );
            Map<String, MarketOdds> odds = client.fetchOddsByState();
            assertTrue(odds.containsKey("NC"));
            assertEquals(0.84, odds.get("NC").demProbability(), 0.0001);
            assertEquals(0.14, odds.get("NC").repProbability(), 0.0001);
        }
    }

    private static class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
