package admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ShopFactory;
import server.life.MonsterInformationProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Private, allowlisted bridge used by the Database CMS for live-only operations.
 *
 * The bridge intentionally has no arbitrary SQL, command, or reflection endpoint. Set
 * COSMIC_BRIDGE_TOKEN to enable it; bind address and port default to 127.0.0.1:8787.
 */
public final class CmsBridgeServer {
    private static final Logger log = LoggerFactory.getLogger(CmsBridgeServer.class);
    private final String token;
    private HttpServer server;

    public CmsBridgeServer(String token) {
        this.token = token;
    }

    public static CmsBridgeServer fromEnvironment() {
        String token = System.getenv("COSMIC_BRIDGE_TOKEN");
        return token == null || token.isBlank() ? null : new CmsBridgeServer(token);
    }

    public void start() {
        String host = System.getenv().getOrDefault("COSMIC_BRIDGE_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("COSMIC_BRIDGE_PORT", "8787"));
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/internal/admin/health", exchange -> handle(exchange, "GET", this::health));
            server.createContext("/internal/admin/cache/drops/reload",
                    exchange -> handle(exchange, "POST", this::reloadDrops));
            server.createContext("/internal/admin/cache/shops/reload",
                    exchange -> handle(exchange, "POST", this::reloadShops));
            server.start();
            log.info("CMS bridge listening on {}:{}", host, port);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start CMS bridge", exception);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    private void handle(HttpExchange exchange, String method, Handler handler) throws IOException {
        try (exchange) {
            if (!method.equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            handler.handle(exchange);
        } catch (Exception exception) {
            log.warn("CMS bridge request failed", exception);
            respond(exchange, 500, "{\"error\":\"internal_error\"}");
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied);
    }

    private void health(HttpExchange exchange) throws IOException {
        Server cosmic = Server.getInstance();
        int onlinePlayers = cosmic.getWorlds().stream()
                .mapToInt(world -> world.getPlayerStorage().getSize())
                .sum();
        String json = """
                {"status":"UP","instanceId":"%s","startedAt":"%s","worlds":%d,"channels":%d,"onlinePlayers":%d}
                """.formatted(Long.toString(Server.uptime), Instant.ofEpochMilli(Server.uptime),
                cosmic.getWorldsSize(), cosmic.getAllChannels().size(), onlinePlayers).trim();
        respond(exchange, 200, json);
    }

    private void reloadDrops(HttpExchange exchange) throws IOException {
        MonsterInformationProvider.getInstance().clearDrops();
        respond(exchange, 200, "{\"reloaded\":true,\"cache\":\"drops\"}");
    }

    private void reloadShops(HttpExchange exchange) throws IOException {
        ShopFactory.getInstance().reloadShops();
        respond(exchange, 200, "{\"reloaded\":true,\"cache\":\"shops\"}");
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
