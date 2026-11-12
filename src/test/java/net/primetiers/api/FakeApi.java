package net.primetiers.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A local stand-in for api.primetiers.net that serves canned responses and records requests. */
final class FakeApi implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        static Reply error(int status, String message) {
            return new Reply(status, "{\"error\":\"" + message + "\"}", Map.of());
        }
    }

    /** Requests received, as decoded path + query. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final Map<String, Function<String, Reply>> routes = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Serves {@code reply} for requests whose path (without query) equals {@code path}. */
    FakeApi on(String path, Function<String, Reply> reply) {
        routes.put(path, reply);
        return this;
    }

    FakeApi on(String path, Reply reply) {
        return on(path, q -> reply);
    }

    long count(String prefix) {
        return requests.stream().filter(r -> r.startsWith(prefix)).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        String query = rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
        requests.add(query.isEmpty() ? path : path + "?" + query);
        userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));

        Function<String, Reply> route = routes.get(path);
        Reply reply = route == null ? Reply.error(404, "not found") : route.apply(query);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        reply.headers().forEach(ex.getResponseHeaders()::set);
        ex.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
