package app.opencodemobile;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public final class ServerConnectionCheck {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = "Basic test".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            String response = "{}";
            if (path.equals("/api/pair")) {
                status = exchange.getRequestMethod().equals("POST") ? status : 405;
                response = "{\"code\":\"example_code-1\"}";
            }
            if (path.equals("/redirect")) {
                status = 302;
                exchange.getResponseHeaders().set("Location", "/api/info");
            }
            if (path.equals("/large")) response = "x".repeat(65537);
            if (path.equals("/shutdown") && status == 200) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assert ServerConnection.request(base, "api/info", "GET", "Basic test").equals("{}");
            assert ServerConnection.request(base, "api/pair", "POST", "Basic test").contains("example_code-1");
            assert ServerConnection.request(base, "shutdown", "POST", "Basic test").equals("");
            rejects(base, "api/info", "bad");
            rejects(base, "api/pair", "Basic test");
            rejects(base, "redirect", "Basic test");
            rejects(base, "large", "Basic test");
            System.out.println("ServerConnection checks passed (auth, pairing, redirect, response limit).");
        } finally {
            server.stop(0);
        }
    }

    private static void rejects(String base, String path, String auth) throws Exception {
        try {
            ServerConnection.request(base, path, "GET", auth);
            throw new AssertionError("Unexpected success: " + path);
        } catch (IOException expected) {
            // HTTP errors and oversized bodies must never count as a healthy server.
        }
    }
}
