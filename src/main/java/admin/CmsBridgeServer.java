package admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ShopFactory;
import server.agent.AgentManagedCharacter;
import server.agent.AgentPilotTickResult;
import server.agent.AgentProfile;
import server.agent.AgentRepository;
import server.agent.AgentRuntimeModule;
import server.life.MonsterInformationProvider;
import server.runtime.RuntimeModuleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
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
    private final AgentRepository agentRepository = new AgentRepository();
    private HttpServer server;
    private ExecutorService executor;

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
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/internal/admin/health", exchange -> handle(exchange, "GET", this::health));
            server.createContext("/internal/admin/cache/drops/reload",
                    exchange -> handle(exchange, "POST", this::reloadDrops));
            server.createContext("/internal/admin/cache/shops/reload",
                    exchange -> handle(exchange, "POST", this::reloadShops));
            server.createContext("/internal/admin/agents/",
                    exchange -> handle(exchange, "POST", this::agentAction));
            server.start();
            log.info("CMS bridge listening on {}:{}", host, port);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start CMS bridge", exception);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.close();
            executor = null;
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

    private void agentAction(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/internal/admin/agents/";
        String[] parts = path.substring(prefix.length()).split("/");
        if (parts.length != 2) {
            respond(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }

        int profileId;
        try {
            profileId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException exception) {
            respond(exchange, 400, "{\"error\":\"invalid_agent_profile_id\"}");
            return;
        }

        String action = parts[1];
        Optional<AgentRuntimeModule> module = RuntimeModuleManager.getInstance().findModule(AgentRuntimeModule.class);
        if (module.isEmpty()) {
            respond(exchange, 409, "{\"error\":\"agent_runtime_unavailable\",\"message\":\"Agent runtime module is not registered\"}");
            return;
        }

        try {
            Optional<AgentProfile> profile = agentRepository.findById(profileId);
            if (profile.isEmpty()) {
                respond(exchange, 404, "{\"error\":\"agent_not_found\"}");
                return;
            }

            switch (action) {
                case "prepare" -> respond(exchange, 200, prepareAgent(module.get(), profile.get()));
                case "enter" -> respond(exchange, 200, enterAgent(module.get(), profile.get()));
                case "release" -> respond(exchange, 200, releaseAgent(module.get(), profile.get()));
                case "tick" -> tickAgent(exchange, module.get(), profile.get());
                default -> respond(exchange, 404, "{\"error\":\"unknown_agent_action\"}");
            }
        } catch (Exception exception) {
            log.warn("Agent bridge action {} failed for profile {}", action, profileId, exception);
            respond(exchange, 500, "{\"error\":\"agent_action_failed\",\"message\":\"" + json(exception.getMessage()) + "\"}");
        }
    }

    private String prepareAgent(AgentRuntimeModule module, AgentProfile profile) throws Exception {
        Optional<AgentManagedCharacter> managed = module.spawnCoordinator().prepare(profile);
        return agentStatusJson("prepare", managed, module);
    }

    private String enterAgent(AgentRuntimeModule module, AgentProfile profile) throws Exception {
        Optional<AgentManagedCharacter> managed = module.spawnCoordinator().enterWorld(profile);
        return agentStatusJson("enter", managed, module);
    }

    private String releaseAgent(AgentRuntimeModule module, AgentProfile profile) {
        module.spawnCoordinator().release(profile, "Released through Server CMS bridge");
        return """
                {"action":"release","released":true,"profileId":%d,"preparedCount":%d,"enteredCount":%d}
                """.formatted(profile.id(), module.spawnCoordinator().preparedCount(), module.spawnCoordinator().enteredCount()).trim();
    }

    private void tickAgent(HttpExchange exchange, AgentRuntimeModule module, AgentProfile profile) throws IOException, Exception {
        Optional<AgentManagedCharacter> managed = module.spawnCoordinator().findEntered(profile.id());
        if (managed.isEmpty()) {
            respond(exchange, 409, "{\"error\":\"agent_not_entered\",\"message\":\"Agent must be entered before ticking\"}");
            return;
        }

        AgentPilotTickResult result = module.pilotService().dryRunTick(managed.get());
        String json = """
                {"action":"tick","profileId":%d,"intent":"%s","dispatchStatus":"%s","message":"%s","preparedCount":%d,"enteredCount":%d}
                """.formatted(profile.id(), result.intent().type(), result.dispatchResult().status(),
                json(result.message()), module.spawnCoordinator().preparedCount(), module.spawnCoordinator().enteredCount()).trim();
        respond(exchange, 200, json);
    }

    private String agentStatusJson(String action, Optional<AgentManagedCharacter> managed, AgentRuntimeModule module) {
        if (managed.isEmpty()) {
            return """
                    {"action":"%s","accepted":false,"preparedCount":%d,"enteredCount":%d}
                    """.formatted(action, module.spawnCoordinator().preparedCount(), module.spawnCoordinator().enteredCount()).trim();
        }

        AgentManagedCharacter agent = managed.get();
        return """
                {"action":"%s","accepted":true,"profileId":%d,"characterId":%d,"sessionId":%d,"entered":%s,"preparedCount":%d,"enteredCount":%d}
                """.formatted(action, agent.profileId(), agent.characterId(), agent.session().id(), agent.enteredWorld(),
                module.spawnCoordinator().preparedCount(), module.spawnCoordinator().enteredCount()).trim();
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
