package com.cosmic.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AgentVirtualClientSystem {
    private final JdbcTemplate cmsJdbc;
    private final ObjectMapper mapper;
    private final AgentSpawnPositionResolver spawnPositionResolver;
    private final AgentMapGeometryRepository geometryRepository;
    private final AgentNavigationExecutionSystem navigationExecutionSystem;
    private final AgentMovementRuntimeSystem movementRuntimeSystem;
    private final AgentDecisionSystem decisionSystem;
    private final AgentNpcInteractionSystem npcInteractionSystem = new AgentNpcInteractionSystem();
    private final AgentCombatSystem combatSystem = new AgentCombatSystem();
    private final AgentSustainSystem sustainSystem = new AgentSustainSystem();
    private final String gameHost;
    private final int loginPort;
    private final int channelPortBase;
    private final int worldPortStride;
    private final int connectTimeoutMillis;
    private final int decisionIntervalMillis;
    private final boolean sendLoginPackets;
    private final boolean sendMovementPackets;
    private final boolean sendNpcPackets;
    private final boolean sendLootPackets;
    private final boolean sendSocialPackets;
    private final boolean sendInventoryPackets;
    private final boolean sendCombatPackets;
    private final boolean sendMobMovementPackets;
    private final int mobMovementIntervalMillis;
    private final boolean sustainEnabled;
    private final int sustainHpThreshold;
    private final int sustainHpItemId;
    private final int sustainHpItemSlot;
    private final boolean sustainVirtualDebtAllowed;
    private final Map<Integer, VirtualSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, LiveConnection> liveConnections = new ConcurrentHashMap<>();
    private final ExecutorService packetPumpExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-virtual-client-pump");
        thread.setDaemon(true);
        return thread;
    });

    public AgentVirtualClientSystem(JdbcTemplate cmsJdbc,
                                    ObjectMapper mapper,
                                    AgentSpawnPositionResolver spawnPositionResolver,
                                    AgentMapGeometryRepository geometryRepository,
                                    AgentNavigationExecutionSystem navigationExecutionSystem,
                                    AgentMovementRuntimeSystem movementRuntimeSystem,
                                    AgentDecisionSystem decisionSystem,
                                    @Value("${cosmic.game.host}") String gameHost,
                                    @Value("${cosmic.game.login-port}") int loginPort,
                                    @Value("${cosmic.game.channel-port-base}") int channelPortBase,
                                    @Value("${cosmic.game.world-port-stride}") int worldPortStride,
                                    @Value("${cosmic.game.connect-timeout-millis}") int connectTimeoutMillis,
                                    @Value("${cosmic.agent-runtime.decision-millis:250}") int decisionIntervalMillis,
                                    @Value("${cosmic.agent-runtime.send-login-packets:false}") boolean sendLoginPackets,
                                    @Value("${cosmic.agent-runtime.send-movement-packets:false}") boolean sendMovementPackets,
                                    @Value("${cosmic.agent-runtime.send-npc-packets:false}") boolean sendNpcPackets,
                                    @Value("${cosmic.agent-runtime.send-loot-packets:false}") boolean sendLootPackets,
                                    @Value("${cosmic.agent-runtime.send-social-packets:false}") boolean sendSocialPackets,
                                    @Value("${cosmic.agent-runtime.send-inventory-packets:false}") boolean sendInventoryPackets,
                                    @Value("${cosmic.agent-runtime.send-combat-packets:false}") boolean sendCombatPackets,
                                    @Value("${cosmic.agent-runtime.send-mob-movement-packets:false}") boolean sendMobMovementPackets,
                                    @Value("${cosmic.agent-runtime.mob-movement-millis:250}") int mobMovementIntervalMillis,
                                    @Value("${cosmic.agent-runtime.sustain.enabled:true}") boolean sustainEnabled,
                                    @Value("${cosmic.agent-runtime.sustain.hp-threshold:30}") int sustainHpThreshold,
                                    @Value("${cosmic.agent-runtime.sustain.hp-item-id:0}") int sustainHpItemId,
                                    @Value("${cosmic.agent-runtime.sustain.hp-item-slot:0}") int sustainHpItemSlot,
                                    @Value("${cosmic.agent-runtime.sustain.virtual-debt-allowed:false}") boolean sustainVirtualDebtAllowed) {
        this.cmsJdbc = cmsJdbc;
        this.mapper = mapper;
        this.spawnPositionResolver = spawnPositionResolver;
        this.geometryRepository = geometryRepository;
        this.navigationExecutionSystem = navigationExecutionSystem;
        this.movementRuntimeSystem = movementRuntimeSystem;
        this.decisionSystem = decisionSystem;
        this.gameHost = gameHost;
        this.loginPort = loginPort;
        this.channelPortBase = channelPortBase;
        this.worldPortStride = worldPortStride;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.decisionIntervalMillis = decisionIntervalMillis;
        this.sendLoginPackets = sendLoginPackets;
        this.sendMovementPackets = sendMovementPackets;
        this.sendNpcPackets = sendNpcPackets;
        this.sendLootPackets = sendLootPackets;
        this.sendSocialPackets = sendSocialPackets;
        this.sendInventoryPackets = sendInventoryPackets;
        this.sendCombatPackets = sendCombatPackets;
        this.sendMobMovementPackets = sendMobMovementPackets;
        this.mobMovementIntervalMillis = Math.max(100, mobMovementIntervalMillis);
        this.sustainEnabled = sustainEnabled;
        this.sustainHpThreshold = sustainHpThreshold;
        this.sustainHpItemId = sustainHpItemId;
        this.sustainHpItemSlot = sustainHpItemSlot;
        this.sustainVirtualDebtAllowed = sustainVirtualDebtAllowed;
    }

    public Map<String, Object> perform(int profileId, String action) {
        return switch (action) {
            case "prepare" -> prepare(profileId);
            case "enter" -> enter(profileId);
            case "tick" -> tick(profileId);
            case "release" -> release(profileId, "Released through Agent CMS");
            default -> throw new ResponseStatusException(NOT_FOUND, "Unknown virtual-client action");
        };
    }

    public Map<String, Object> snapshot(int profileId) {
        Map<String, Object> profile = profile(profileId);
        Optional<VirtualSession> session = sessionFromLatest(profile);
        Map<String, Object> result = baseResult(profile, "snapshot", session.orElse(null));
        result.put("status", session.map(value -> value.state().name()).orElse("OFFLINE"));
        result.put("ready", liveConnections.containsKey(profileId)
                || session.map(value -> value.state() == VirtualClientState.CHANNEL_CONNECTED).orElse(false));
        result.put("manualControl", Map.of(
                "keyHoldSupported", true,
                "actionSupported", true,
                "sendLoginPackets", sendLoginPackets,
                "sendMovementPackets", sendMovementPackets,
                "sendNpcPackets", sendNpcPackets,
                "sendLootPackets", sendLootPackets,
                "sendSocialPackets", sendSocialPackets,
                "sendInventoryPackets", sendInventoryPackets,
                "sendCombatPackets", sendCombatPackets,
                "sendMobMovementPackets", sendMobMovementPackets
        ));
        result.put("perception", liveConnections.containsKey(profileId)
                ? liveConnections.get(profileId).snapshot()
                : Map.of("connected", false, "message", "No live channel packet pump is active"));
        return result;
    }

    public Map<String, Object> manualKey(int profileId, String key, boolean pressed) {
        Map<String, Object> profile = profile(profileId);
        Optional<VirtualSession> session = sessionFromLatest(profile);
        Map<String, Object> result = baseResult(profile, pressed ? "manual-key-start" : "manual-key-stop", session.orElse(null));
        String normalizedKey = key == null ? "" : key.trim().toUpperCase();
        result.put("key", normalizedKey);
        result.put("pressed", pressed);
        result.put("status", session.isPresent() ? "RECORDED" : "BLOCKED");
        result.put("detail", session.isPresent()
                ? "Manual key state recorded. Movement emission is guarded by AGENT_CLIENT_SEND_MOVEMENT_PACKETS."
                : "Prepare or enter the agent before sending manual key state.");
        if (session.isPresent()) {
            LiveConnection connection = liveConnections.get(profileId);
            if (connection == null) {
                result.put("movementPacket", Map.of(
                        "sent", false,
                        "blocked", "No live channel packet pump is active. Enter world before manual movement."
                ));
            } else {
                if (pressed) {
                    connection.manualKeys.add(normalizedKey);
                    result.put("movementPacket", maybeSendManualMovementStep(connection, normalizedKey));
                } else {
                    if ("ALL".equals(normalizedKey) || "STOP".equals(normalizedKey)) {
                        connection.manualKeys.clear();
                    } else {
                        connection.manualKeys.remove(normalizedKey);
                    }
                    result.put("movementPacket", Map.of(
                            "sent", false,
                            "stopped", true,
                            "activeKeys", new ArrayList<>(connection.manualKeys)
                    ));
                }
            }
        }
        session.ifPresent(value -> logAction(profileId, value.sessionId(), pressed ? "MANUAL_KEY_START" : "MANUAL_KEY_STOP",
                "OK", value.world(), value.channel(), value.mapId(), "KEY", null,
                normalizedKey, Map.of("key", normalizedKey, "pressed", pressed)));
        return result;
    }

    public Map<String, Object> manualAction(int profileId, String action, Map<String, Object> payload) {
        Map<String, Object> profile = profile(profileId);
        Optional<VirtualSession> session = sessionFromLatest(profile);
        Map<String, Object> result = baseResult(profile, "manual-action", session.orElse(null));
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        result.put("manualAction", normalizedAction);
        result.put("payload", payload);
        result.put("status", session.isPresent() ? "RECORDED" : "BLOCKED");
        result.put("detail", session.isPresent()
                ? "Manual action accepted by agent-client control plane; packet emission depends on the matching live-send guard."
                : "Prepare or enter the agent before sending manual actions.");
        if (session.isPresent()) {
            LiveConnection connection = liveConnections.get(profileId);
            if (connection == null) {
                result.put("packet", Map.of(
                        "sent", false,
                        "blocked", "No live channel packet pump is active. Enter world before manual actions."
                ));
            } else {
                result.put("packet", executeManualAction(connection, normalizedAction, payload == null ? Map.of() : payload));
            }
        }
        session.ifPresent(value -> logAction(profileId, value.sessionId(), "MANUAL_" + normalizedAction,
                "OK", value.world(), value.channel(), value.mapId(), "ACTION", null,
                normalizedAction, payload == null ? Map.of() : payload));
        return result;
    }

    public Map<String, Object> prepare(int profileId) {
        Map<String, Object> profile = profile(profileId);
        if (!truthy(profile.get("enabled"))) {
            return blocked(profile, "PREPARE", "Agent profile is disabled");
        }
        if (numeric(profile.get("loggedin"), 0) != 0) {
            return blocked(profile, "PREPARE", "Account is already logged in through a client");
        }

        closeLiveConnection(profileId);
        sessionFromLatest(profile).ifPresent(existing -> closeSession(existing, "Replaced by a new virtual-client prepare"));
        int world = numeric(profile.get("world"), 0);
        int channel = sanitizeChannel(numeric(profile.get("deployment_channel"), 1));
        int mapId = numeric(profile.get("map"), 0);
        long sessionId = openSession(profileId, numeric(profile.get("character_id"), 0), world, channel, mapId,
                "PREPARED", "Virtual client prepared; protocol login is pending");
        VirtualSession session = new VirtualSession(sessionId, profileId, numeric(profile.get("character_id"), 0),
                world, channel, mapId, VirtualClientState.PREPARED, Instant.now());
        sessions.put(profileId, session);

        Map<String, Object> result = baseResult(profile, "PREPARE", session);
        result.put("accepted", true);
        result.put("ready", false);
        result.put("status", "PREPARED");
        result.put("detail", "AgentVirtualClientSystem prepared an external headless-client session shell.");
        logAction(profileId, sessionId, "VIRTUAL_CLIENT_PREPARE", "OK", world, channel, mapId, null, null,
                "Prepared virtual-client session", result);
        return result;
    }

    public Map<String, Object> enter(int profileId) {
        Map<String, Object> profile = profile(profileId);
        VirtualSession session = sessions.computeIfAbsent(profileId, ignored -> sessionFromLatest(profile)
                .orElseGet(() -> {
                    Map<String, Object> prepared = prepare(profileId);
                    return sessions.get(profileId);
                }));
        if (session == null) {
            return blocked(profile, "ENTER", "No prepared virtual-client session");
        }

        int channelPort = channelPort(session.world(), session.channel());
        Map<String, Object> socketCheck = new LinkedHashMap<>();
        Map<String, Object> loginHandshake = readLoginHello(gameHost, loginPort);
        socketCheck.put("login", loginHandshake);
        socketCheck.put("channel", probe(gameHost, channelPort));
        boolean reachable = Boolean.TRUE.equals(loginHandshake.get("reachable"))
                && Boolean.TRUE.equals(loginHandshake.get("handshakeAccepted"))
                && Boolean.TRUE.equals(((Map<?, ?>) socketCheck.get("channel")).get("reachable"));

        Optional<RuntimeCredential> credential = runtimeCredential(profileId, String.valueOf(profile.get("account_name")));
        Map<String, Object> loginPacket = credential
                .map(value -> AgentLoginPacketFactory.describeLoginPassword(value.accountName(), value.passwordSecret()))
                .orElseGet(() -> {
                    Map<String, Object> missing = new LinkedHashMap<>();
                    missing.put("credentialRequired", true);
                    missing.put("next", "Create the agent through Agent CMS or add a runtime credential before protocol login");
                    return missing;
                });
        if (reachable && credential.isPresent()) {
            Map<String, Object> protocol = objectMap(loginHandshake.get("protocol"));
            loginPacket.putAll(prepareEncryptedLoginFrame(protocol, credential.get()));
            if (sendLoginPackets) {
                closeLiveConnection(profileId);
                loginPacket.put("liveProbe", openGameplayConnection(profileId, credential.get(), session));
            }
        }

        VirtualClientState nextState = !reachable
                ? VirtualClientState.FAILED
                : liveConnections.containsKey(profileId) ? VirtualClientState.CHANNEL_CONNECTED
                : credential.isPresent() ? VirtualClientState.ENCRYPTED_LOGIN_READY : VirtualClientState.CREDENTIAL_REQUIRED;
        VirtualSession updated = session.withState(nextState);
        sessions.put(profileId, updated);
        updateSession(updated, nextState.name(), reachable ? nextState.taskDescription()
                : "Cosmic login hello or selected channel socket is not reachable");

        Map<String, Object> result = baseResult(profile, "ENTER", updated);
        result.put("accepted", reachable && credential.isPresent());
        result.put("ready", liveConnections.containsKey(profileId));
        result.put("status", nextState.name());
        result.put("detail", reachable ? nextState.taskDescription()
                : "Cannot enter until the Cosmic login hello and selected channel socket are reachable.");
        result.put("socketCheck", socketCheck);
        result.put("protocol", loginHandshake.get("protocol"));
        result.put("loginPacket", loginPacket);
        logAction(profileId, updated.sessionId(), "VIRTUAL_CLIENT_ENTER", reachable ? "OK" : "BLOCKED",
                updated.world(), updated.channel(), updated.mapId(), null, null,
                String.valueOf(result.get("detail")), result);
        return result;
    }

    public Map<String, Object> tick(int profileId) {
        Map<String, Object> profile = profile(profileId);
        VirtualSession session = sessions.computeIfAbsent(profileId, ignored -> sessionFromLatest(profile).orElse(null));
        if (session == null) {
            return blocked(profile, "TICK", "No prepared virtual-client session");
        }
        LiveConnection connection = liveConnections.get(profileId);
        if (connection != null) {
            syncObservedSession(connection);
            VirtualSession updated = connection.virtualSession.withState(VirtualClientState.CHANNEL_CONNECTED);
            connection.virtualSession = updated;
            sessions.put(profileId, updated);
            updateSession(updated, "CHANNEL_CONNECTED", "Virtual client channel socket is open");

            Map<String, Object> result = baseResult(profile, "TICK", updated);
            Map<String, Object> channelSnapshot = connection.snapshot();
            int observedMapId = numeric(objectMap(channelSnapshot.get("observed")).get("mapId"), updated.mapId());
            Map<String, Object> decision = decisionSystem.decide(profileId, observedMapId, channelSnapshot);
            Map<String, Object> intentPacket = maybeExecuteDecisionIntent(connection, decision);
            Map<String, Object> movementPacket = connection.activeNpcConversation
                    ? suppressMovementDuringNpc(connection, "tick")
                    : maybeSendNavigationStep(connection, decision);
            result.put("accepted", true);
            result.put("dispatchStatus", "CHANNEL_SOCKET_OPEN");
            result.put("status", "CHANNEL_CONNECTED");
            result.put("detail", "Virtual client is connected to the selected channel; packet pump is keeping the session alive.");
            result.put("decision", decision);
            result.put("channelPump", channelSnapshot);
            result.put("movementPacket", movementPacket);
            result.put("intentPacket", intentPacket);
            logAction(profileId, updated.sessionId(), "VIRTUAL_CLIENT_TICK", "OK", updated.world(), updated.channel(),
                    updated.mapId(), null, null, "Virtual client channel socket is open", result);
            return result;
        }
        VirtualSession updated = session.withState(VirtualClientState.PROTOCOL_PENDING);
        sessions.put(profileId, updated);
        updateSession(updated, "PROTOCOL_PENDING", "Virtual client tick reached protocol boundary");

        Map<String, Object> result = baseResult(profile, "TICK", updated);
        result.put("accepted", true);
            result.put("dispatchStatus", session.state() == VirtualClientState.ENCRYPTED_LOGIN_READY
                ? "SERVERLIST_PACKET_PENDING" : "LOGIN_PACKET_PENDING");
        result.put("status", "PROTOCOL_PENDING");
        result.put("detail", "No gameplay dispatch yet. v83 encrypted socket login and channel transfer are the next slices.");
        logAction(profileId, updated.sessionId(), "VIRTUAL_CLIENT_TICK", "OK", updated.world(), updated.channel(),
                updated.mapId(), null, null, "Virtual client tick reached protocol boundary", result);
        return result;
    }

    Map<String, Object> pulseLiveConnections() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("system", "AgentRuntimePulseScheduler");
        result.put("movement", pulseLiveMovement());
        result.put("decisions", pulseLiveDecisions());
        result.put("at", Instant.now().toString());
        return result;
    }

    Map<String, Object> pulseLiveMovement() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("system", "AgentRuntimeMovementPulse");
        result.put("liveConnections", liveConnections.size());
        int movementPulses = 0;
        int mobMovementPulses = 0;
        long now = System.currentTimeMillis();
        for (LiveConnection connection : liveConnections.values()) {
            if (!connection.running.get() || connection.socket.isClosed()) {
                continue;
            }
            syncObservedSession(connection);
            boolean autonomousEnabled = refreshAutonomousEnabled(connection, now);
            if (maybePulseControlledMonsters(connection, now)) {
                mobMovementPulses++;
            }
            if (!connection.manualKeys.isEmpty()) {
                maybeSendHeldManualMovementStep(connection);
                movementPulses++;
                continue;
            }
            if (connection.activeNpcConversation) {
                connection.lastAutonomousIntent = null;
                continue;
            }
            if (!autonomousEnabled) {
                connection.lastAutonomousIntent = null;
                continue;
            }
            if (connection.manualKeys.isEmpty() && connection.lastAutonomousIntent != null
                    && connection.lastMovementAtMillis > 0
                    && now - connection.lastMovementAtMillis >= AgentPhysicsSystem.TICK_MILLIS
            ) {
                if (sendRuntimePulse(connection, connection.lastAutonomousIntent, "AUTONOMOUS_MOVEMENT_CONTINUATION", now)) {
                    movementPulses++;
                }
            }
            if (connection.manualKeys.isEmpty() && connection.motionState != null
                    && connection.motionState.mode() != AgentPhysicsSystem.MotionMode.GROUNDED
                    && now - connection.lastMovementAtMillis >= AgentPhysicsSystem.TICK_MILLIS) {
                AgentPhysicsSystem.MovementIntent intent = connection.lastAutonomousIntent == null
                        ? AgentPhysicsSystem.MovementIntent.idle()
                        : connection.lastAutonomousIntent;
                if (sendRuntimePulse(connection, intent, "AIRBORNE_MOVEMENT_CONTINUATION", now)) {
                    movementPulses++;
                }
            }
        }
        result.put("movementPulses", movementPulses);
        result.put("mobMovementPulses", mobMovementPulses);
        result.put("at", Instant.now().toString());
        return result;
    }

    private boolean maybePulseControlledMonsters(LiveConnection connection, long now) {
        if (!sendMobMovementPackets || connection.controlledMonsters.isEmpty()) {
            return false;
        }
        if (connection.lastMobMovementAtMillis > 0 && now - connection.lastMobMovementAtMillis < mobMovementIntervalMillis) {
            return false;
        }
        int sent = 0;
        for (ControlledMonsterState monster : connection.controlledMonsters.values()) {
            if (!monster.hasCoordinates()) {
                continue;
            }
            int fromX = monster.x;
            int fromY = monster.y;
            int step = 10 * monster.direction;
            int nextX = fromX + step;
            if (Math.abs(nextX - monster.originX) > 36) {
                monster.direction *= -1;
                nextX = fromX + (10 * monster.direction);
            }
            int stance = monster.direction >= 0 ? 2 : 3;
            try {
                int moveId = connection.nextMobMoveId();
                byte[] body = AgentMobMovementPacketFactory.moveLifeBody(
                        monster.objectId,
                        moveId,
                        fromX,
                        fromY,
                        nextX,
                        fromY,
                        monster.foothold,
                        stance,
                        mobMovementIntervalMillis,
                        -1
                );
                connection.sendClientPacket(body);
                monster.x = nextX;
                monster.y = fromY;
                monster.stance = stance;
                monster.lastMovedAtMillis = now;
                connection.mergeControlledMonsterPosition(monster);
                connection.addObservation(Map.of(
                        "event", "CONTROLLED_MONSTER_MOVE_SENT",
                        "objectId", monster.objectId,
                        "mobId", monster.mobId,
                        "moveId", moveId,
                        "x", nextX,
                        "y", fromY,
                        "bodyLength", body.length,
                        "at", Instant.now().toString()
                ));
                sent++;
            } catch (Exception exception) {
                connection.addObservation(Map.of(
                        "event", "CONTROLLED_MONSTER_MOVE_FAILED",
                        "objectId", monster.objectId,
                        "error", exception.getClass().getSimpleName(),
                        "detail", String.valueOf(exception.getMessage()),
                        "at", Instant.now().toString()
                ));
            }
        }
        if (sent > 0) {
            connection.lastMobMovementAtMillis = now;
        }
        return sent > 0;
    }

    Map<String, Object> pulseLiveDecisions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("system", "AgentRuntimeDecisionPulse");
        result.put("liveConnections", liveConnections.size());
        int decisionPulses = 0;
        long now = System.currentTimeMillis();
        for (LiveConnection connection : liveConnections.values()) {
            if (!connection.running.get() || connection.socket.isClosed()) {
                continue;
            }
            syncObservedSession(connection);
            if (!connection.manualKeys.isEmpty()) {
                continue;
            }
            boolean autonomousEnabled = refreshAutonomousEnabled(connection, now);
            if (!autonomousEnabled) {
                connection.lastAutonomousIntent = null;
                continue;
            }
            if (now - connection.lastDecisionAtMillis >= decisionIntervalMillis) {
                Map<String, Object> channelSnapshot = connection.snapshot();
                int observedMapId = numeric(objectMap(channelSnapshot.get("observed")).get("mapId"), connection.virtualSession.mapId());
                Map<String, Object> decision = decisionSystem.decide(connection.profileId, observedMapId, channelSnapshot);
                maybeExecuteDecisionIntent(connection, decision);
                if (connection.activeNpcConversation) {
                    suppressMovementDuringNpc(connection, "decision-pulse");
                } else {
                    maybeSendNavigationStep(connection, decision);
                }
                connection.lastDecisionAtMillis = now;
                decisionPulses++;
            }
        }
        result.put("decisionPulses", decisionPulses);
        result.put("at", Instant.now().toString());
        return result;
    }

    private boolean refreshAutonomousEnabled(LiveConnection connection, long now) {
        if (now - connection.lastAutonomyRefreshAtMillis < 1_000L) {
            return connection.autonomousEnabled;
        }
        connection.lastAutonomyRefreshAtMillis = now;
        try {
            Integer value = cmsJdbc.queryForObject("SELECT autonomous_enabled FROM agent_profiles WHERE id=?",
                    Integer.class, connection.profileId);
            boolean enabled = value != null && value != 0;
            if (!enabled) {
                connection.lastAutonomousIntent = null;
            }
            connection.autonomousEnabled = enabled;
            return enabled;
        } catch (Exception ignored) {
            connection.lastAutonomousIntent = null;
            connection.autonomousEnabled = false;
            return false;
        }
    }

    private boolean sendRuntimePulse(LiveConnection connection, AgentPhysicsSystem.MovementIntent intent,
                                     String eventName, long now) {
        if (!sendMovementPackets || connection.observedX == null || connection.observedY == null) {
            return false;
        }
        int mapId = connection.observedMapId == null ? connection.virtualSession.mapId() : connection.observedMapId;
        int foothold = connection.observedFoothold == null ? 0 : connection.observedFoothold;
        try {
            AgentMovementRuntimeSystem.RuntimeStep step = movementRuntimeSystem.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                    connection.profileId,
                    connection.characterId,
                    mapId,
                    connection.observedX,
                    connection.observedY,
                    foothold,
                    connection.probeDirection,
                    Optional.ofNullable(connection.motionState),
                    intent
            ));
            connection.sendClientPacket(step.body());
            connection.lastMovementAtMillis = now;
            connection.applyRuntimeMovement(step.physicsStep());
            connection.addObservation(Map.of(
                    "event", eventName,
                    "physicsEvent", step.physicsStep().event(),
                    "x", step.physicsStep().current().x(),
                    "y", step.physicsStep().current().y(),
                    "at", Instant.now().toString()
            ));
            return true;
        } catch (Exception exception) {
            connection.addObservation(Map.of(
                    "event", eventName + "_FAILED",
                    "error", exception.getClass().getSimpleName(),
                    "detail", String.valueOf(exception.getMessage()),
                    "at", Instant.now().toString()
            ));
            return false;
        }
    }

    public Map<String, Object> release(int profileId, String reason) {
        Map<String, Object> profile = profile(profileId);
        closeLiveConnection(profileId);
        VirtualSession session = sessions.remove(profileId);
        if (session == null) {
            session = sessionFromLatest(profile).orElse(null);
        }
        if (session == null) {
            Map<String, Object> result = baseResult(profile, "RELEASE", null);
            result.put("accepted", true);
            result.put("status", "STOPPED");
            result.put("detail", "No open virtual-client session existed.");
            return result;
        }

        cmsJdbc.update("""
                UPDATE agent_runtime_sessions
                SET state='STOPPED',
                    current_task=?,
                    stop_reason=?,
                    ended_at=CURRENT_TIMESTAMP,
                    last_tick_at=CURRENT_TIMESTAMP
                WHERE id=? AND ended_at IS NULL
                """, reason, reason, session.sessionId());
        Map<String, Object> result = baseResult(profile, "RELEASE", session.withState(VirtualClientState.STOPPED));
        result.put("accepted", true);
        result.put("status", "STOPPED");
        result.put("detail", reason);
        logAction(profileId, session.sessionId(), "VIRTUAL_CLIENT_RELEASE", "OK", session.world(), session.channel(),
                session.mapId(), null, null, reason, result);
        return result;
    }

    private Map<String, Object> blocked(Map<String, Object> profile, String action, String detail) {
        Map<String, Object> result = baseResult(profile, action, null);
        result.put("accepted", false);
        result.put("ready", false);
        result.put("status", "BLOCKED");
        result.put("detail", detail);
        logAction(numeric(profile.get("id"), 0), null, "VIRTUAL_CLIENT_" + action, "BLOCKED",
                numeric(profile.get("world"), 0), sanitizeChannel(numeric(profile.get("deployment_channel"), 1)),
                numeric(profile.get("map"), 0), null, null, detail, result);
        return result;
    }

    private Map<String, Object> baseResult(Map<String, Object> profile, String action, VirtualSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("system", "AgentVirtualClientSystem");
        result.put("action", action.toLowerCase());
        result.put("profileId", profile.get("id"));
        result.put("characterId", profile.get("character_id"));
        result.put("characterName", profile.get("character_name"));
        result.put("accountName", profile.get("account_name"));
        result.put("world", session == null ? profile.get("world") : session.world());
        result.put("channel", session == null ? sanitizeChannel(numeric(profile.get("deployment_channel"), 1)) : session.channel());
        result.put("map", session == null ? profile.get("map") : session.mapId());
        result.put("sessionId", session == null ? null : session.sessionId());
        result.put("loginHost", gameHost);
        result.put("loginPort", loginPort);
        result.put("channelPort", channelPort(numeric(result.get("world"), 0), numeric(result.get("channel"), 1)));
        return result;
    }

    private Map<String, Object> profile(int profileId) {
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT p.*,
                       p.character_name,
                       p.account_name,
                       COALESCE(p.level, 1) level,
                       COALESCE(p.job, 0) job,
                       COALESCE(p.world, 0) world,
                       COALESCE(p.map, 0) map,
                       COALESCE(p.spawnpoint, 0) spawnpoint,
                       COALESCE(p.loggedin, 0) loggedin
                FROM agent_profiles p
                WHERE p.id=?
                """, profileId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Agent profile not found");
        }
        return rows.getFirst();
    }

    private Optional<VirtualSession> sessionFromLatest(Map<String, Object> profile) {
        int profileId = numeric(profile.get("id"), 0);
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT *
                FROM agent_runtime_sessions
                WHERE agent_profile_id=?
                  AND ended_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """, profileId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new VirtualSession(
                ((Number) row.get("id")).longValue(),
                profileId,
                numeric(row.get("character_id"), numeric(profile.get("character_id"), 0)),
                numeric(row.get("world"), numeric(profile.get("world"), 0)),
                sanitizeChannel(numeric(row.get("channel"), numeric(profile.get("deployment_channel"), 1))),
                numeric(row.get("map_id"), numeric(profile.get("map"), 0)),
                VirtualClientState.from(String.valueOf(row.get("state"))),
                Instant.now()
        ));
    }

    private long openSession(int profileId, int characterId, int world, int channel, int mapId, String state, String task) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        cmsJdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_runtime_sessions(agent_profile_id, character_id, world, channel, map_id, state, current_task, last_tick_at)
                    VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, profileId);
            statement.setInt(2, characterId);
            statement.setInt(3, world);
            statement.setInt(4, channel);
            statement.setInt(5, mapId);
            statement.setString(6, state);
            statement.setString(7, task);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(CONFLICT, "Unable to create virtual-client session");
        }
        return key.longValue();
    }

    private void updateSession(VirtualSession session, String state, String task) {
        cmsJdbc.update("""
                UPDATE agent_runtime_sessions
                SET state=?, current_task=?, map_id=?, last_tick_at=CURRENT_TIMESTAMP
                WHERE id=? AND ended_at IS NULL
                """, state, task, session.mapId(), session.sessionId());
    }

    private VirtualSession syncObservedSession(LiveConnection connection) {
        Integer observedMapId = connection.observedMapId;
        if (observedMapId == null || observedMapId <= 0) {
            return connection.virtualSession;
        }
        VirtualSession current = connection.virtualSession;
        if (current.mapId() == observedMapId) {
            return current;
        }
        VirtualSession updated = current.withMap(observedMapId);
        connection.virtualSession = updated;
        sessions.put(connection.profileId, updated);
        cmsJdbc.update("""
                UPDATE agent_runtime_sessions
                SET map_id=?, last_tick_at=CURRENT_TIMESTAMP
                WHERE id=? AND ended_at IS NULL
                """, observedMapId, updated.sessionId());
        cmsJdbc.update("""
                UPDATE agent_profiles
                SET map=?, spawnpoint=COALESCE(?, spawnpoint), updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, observedMapId, connection.observedSpawnPoint, connection.profileId);
        connection.addObservation(Map.of(
                "event", "SESSION_MAP_SYNCED",
                "fromMapId", current.mapId(),
                "toMapId", observedMapId,
                "at", Instant.now().toString()
        ));
        return updated;
    }

    private void closeSession(VirtualSession session, String reason) {
        cmsJdbc.update("""
                UPDATE agent_runtime_sessions
                SET state=?, current_task=?, ended_at=CURRENT_TIMESTAMP, last_tick_at=CURRENT_TIMESTAMP
                WHERE id=? AND ended_at IS NULL
                """, VirtualClientState.STOPPED.name(), reason, session.sessionId());
        sessions.remove(session.profileId(), session);
    }

    private void logAction(int profileId, Long sessionId, String actionType, String status, int world, int channel,
                           int mapId, String targetType, Long targetId, String message, Map<String, Object> details) {
        try {
            cmsJdbc.update("""
                    INSERT INTO agent_action_logs(agent_profile_id, runtime_session_id, action_type, status, world, channel,
                                                  map_id, target_type, target_id, message, details_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, profileId, sessionId, actionType, status, world, channel, mapId, targetType, targetId,
                    truncate(message, 512), mapper.writeValueAsString(details));
        } catch (JsonProcessingException exception) {
            cmsJdbc.update("""
                    INSERT INTO agent_action_logs(agent_profile_id, runtime_session_id, action_type, status, world, channel,
                                                  map_id, target_type, target_id, message, details_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, profileId, sessionId, actionType, status, world, channel, mapId, targetType, targetId,
                    truncate(message, 512), "{\"error\":\"Unable to serialize virtual-client details\"}");
        }
    }

    private Map<String, Object> probe(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("port", port);
        long started = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            result.put("reachable", true);
            result.put("latencyMillis", System.currentTimeMillis() - started);
        } catch (Exception exception) {
            result.put("reachable", false);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("error", exception.getClass().getSimpleName());
        }
        return result;
    }

    private Optional<RuntimeCredential> runtimeCredential(int profileId, String accountName) {
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT account_name, password_secret, secret_format
                FROM agent_runtime_credentials
                WHERE agent_profile_id=?
                LIMIT 1
                """, profileId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        String storedAccountName = String.valueOf(row.getOrDefault("account_name", accountName));
        Object passwordValue = row.get("password_secret");
        String passwordSecret = passwordValue == null ? null : String.valueOf(passwordValue);
        if (passwordSecret == null || passwordSecret.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeCredential(storedAccountName, passwordSecret,
                String.valueOf(row.getOrDefault("secret_format", "PLAINTEXT_LOCAL_DEV"))));
    }

    private Map<String, Object> readLoginHello(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("port", port);
        long started = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(connectTimeoutMillis);
            byte[] lengthBytes = readExactly(socket, 2);
            int helloLength = unsignedShortLE(lengthBytes, 0);
            if (helloLength <= 0 || helloLength > 64) {
                result.put("reachable", true);
                result.put("handshakeAccepted", false);
                result.put("latencyMillis", System.currentTimeMillis() - started);
                result.put("error", "UnexpectedHelloLength");
                result.put("helloLength", helloLength);
                return result;
            }

            byte[] body = readExactly(socket, helloLength);
            Map<String, Object> protocol = parseHello(body, helloLength);
            result.put("reachable", true);
            result.put("handshakeAccepted", true);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("helloLength", helloLength);
            result.put("protocol", protocol);
        } catch (SocketTimeoutException exception) {
            result.put("reachable", false);
            result.put("handshakeAccepted", false);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("error", "SocketTimeoutException");
        } catch (Exception exception) {
            result.put("reachable", false);
            result.put("handshakeAccepted", false);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", exception.getMessage());
        }
        return result;
    }

    private Map<String, Object> prepareEncryptedLoginFrame(Map<String, Object> protocol, RuntimeCredential credential) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            byte[] clientSendIv = hexToBytes(String.valueOf(protocol.get("clientSendIv")));
            byte[] clientReceiveIv = hexToBytes(String.valueOf(protocol.get("clientReceiveIv")));
            AgentMapleCrypto.MapleSession session = AgentMapleCrypto.fromHello(clientSendIv, clientReceiveIv);
            byte[] encrypted = AgentMapleCrypto.encryptClientPacket(
                    AgentLoginPacketFactory.loginPasswordBody(credential.accountName(), credential.passwordSecret()), session);
            result.put("encryptedFrameReady", true);
            result.put("encryptedFrameLength", encrypted.length);
            result.put("sendEnabled", sendLoginPackets);
            result.put("next", sendLoginPackets ? "live login probe enabled" : "set AGENT_CLIENT_SEND_LOGIN_PACKETS=true to perform live login probe");
        } catch (Exception exception) {
            result.put("encryptedFrameReady", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", exception.getMessage());
        }
        return result;
    }

    private Map<String, Object> openGameplayConnection(int profileId, RuntimeCredential credential, VirtualSession virtualSession) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        long started = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(gameHost, loginPort), connectTimeoutMillis);
            socket.setSoTimeout(connectTimeoutMillis);
            byte[] lengthBytes = readExactly(socket, 2);
            int helloLength = unsignedShortLE(lengthBytes, 0);
            byte[] body = readExactly(socket, helloLength);
            Map<String, Object> protocol = parseHello(body, helloLength);
            AgentMapleCrypto.MapleSession session = AgentMapleCrypto.fromHello(
                    hexToBytes(String.valueOf(protocol.get("clientSendIv"))),
                    hexToBytes(String.valueOf(protocol.get("clientReceiveIv"))));
            byte[] encrypted = AgentMapleCrypto.encryptClientPacket(
                    AgentLoginPacketFactory.loginPasswordBody(credential.accountName(), credential.passwordSecret()), session);
            socket.getOutputStream().write(encrypted);
            socket.getOutputStream().flush();

            byte[] response = readEncryptedPacket(socket, session);
            int responseOpcode = response.length >= 2 ? unsignedShortLE(response, 0) : -1;
            boolean loginAccepted = responseOpcode == 0 && response.length >= 6
                    && response[2] == 0 && response[3] == 0 && response[4] == 0 && response[5] == 0;
            result.put("reachable", true);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("responseLength", response.length);
            result.put("responseOpcode", responseOpcode);
            result.put("responseOpcodeName", responseOpcode == 0 ? "LOGIN_STATUS" : "UNKNOWN");
            result.put("loginAccepted", loginAccepted);
            result.put("loginStatusReason", loginAccepted ? 0 : (response.length > 2 ? unsignedByte(response, 2) : null));
            if (loginAccepted) {
                result.put("serverListProbe", requestServerList(socket, session));
                result.put("charListProbe", requestCharList(socket, session,
                        virtualSession.world(), virtualSession.channel()));
                Map<String, Object> serverIp = requestServerIp(socket, session, virtualSession.characterId());
                result.put("serverIp", serverIp);
                if (Boolean.TRUE.equals(serverIp.get("accepted"))) {
                    LiveConnection connection = connectChannel(profileId, virtualSession, serverIp);
                    liveConnections.put(profileId, connection);
                    startChannelPump(connection);
                    result.put("channelConnect", Map.of(
                            "connected", true,
                            "host", connection.host(),
                            "port", connection.port(),
                            "characterId", connection.characterId()
                    ));
                }
            }
        } catch (Exception exception) {
            closeLiveConnection(profileId);
            result.put("reachable", false);
            result.put("latencyMillis", System.currentTimeMillis() - started);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", exception.getMessage());
        }
        return result;
    }

    private Map<String, Object> requestServerIp(Socket socket, AgentMapleCrypto.MapleSession session,
                                                int characterId) throws Exception {
        socket.getOutputStream().write(AgentMapleCrypto.encryptClientPacket(
                AgentLoginPacketFactory.charSelectBody(characterId), session));
        socket.getOutputStream().flush();
        byte[] packet = readEncryptedPacket(socket, session);
        int opcode = packet.length >= 2 ? unsignedShortLE(packet, 0) : -1;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("opcode", opcode);
        result.put("opcodeName", sendOpcodeName(opcode));
        result.put("length", packet.length);
        result.put("characterId", characterId);
        if (opcode == 0x0C && packet.length >= 15) {
            String host = unsignedByte(packet, 4) + "." + unsignedByte(packet, 5) + "."
                    + unsignedByte(packet, 6) + "." + unsignedByte(packet, 7);
            int port = unsignedShortLE(packet, 8);
            int returnedCharacterId = readIntLE(packet, 10);
            result.put("accepted", true);
            result.put("host", host);
            result.put("port", port);
            result.put("returnedCharacterId", returnedCharacterId);
        } else {
            result.put("accepted", false);
            result.put("detail", "Expected SERVER_IP after CHAR_SELECT");
        }
        return result;
    }

    private LiveConnection connectChannel(int profileId, VirtualSession virtualSession, Map<String, Object> serverIp) throws Exception {
        String host = String.valueOf(serverIp.getOrDefault("host", gameHost));
        int port = numeric(serverIp.get("port"), channelPort(virtualSession.world(), virtualSession.channel()));
        Socket channelSocket = new Socket();
        boolean stored = false;
        try {
            channelSocket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            channelSocket.setSoTimeout(Math.max(250, connectTimeoutMillis));
            byte[] lengthBytes = readExactly(channelSocket, 2);
            int helloLength = unsignedShortLE(lengthBytes, 0);
            byte[] body = readExactly(channelSocket, helloLength);
            Map<String, Object> protocol = parseHello(body, helloLength);
            AgentMapleCrypto.MapleSession channelSession = AgentMapleCrypto.fromHello(
                    hexToBytes(String.valueOf(protocol.get("clientSendIv"))),
                    hexToBytes(String.valueOf(protocol.get("clientReceiveIv"))));
            Map<String, Object> profile = profile(profileId);
            LiveConnection connection = new LiveConnection(profileId, virtualSession, virtualSession.characterId(), host, port,
                    channelSocket, channelSession, protocol, Instant.now(), truthy(profile.get("autonomous_enabled")));
            initializeCachedLocation(connection, profile);
            connection.sendClientPacket(AgentLoginPacketFactory.playerLoggedInBody(virtualSession.characterId()));
            stored = true;
            return connection;
        } finally {
            if (!stored) {
                try {
                    channelSocket.close();
                } catch (Exception ignored) {
                    // best-effort cleanup for failed handoff
                }
            }
        }
    }

    private Map<String, Object> requestServerList(Socket socket, AgentMapleCrypto.MapleSession session) throws Exception {
        socket.getOutputStream().write(AgentMapleCrypto.encryptClientPacket(
                AgentLoginPacketFactory.serverListRequestBody(), session));
        socket.getOutputStream().flush();

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> packets = new java.util.ArrayList<>();
        boolean sawEnd = false;
        boolean sawLastConnectedWorld = false;
        boolean sawRecommendedWorld = false;
        for (int i = 0; i < 16; i++) {
            byte[] packet = readEncryptedPacket(socket, session);
            int opcode = packet.length >= 2 ? unsignedShortLE(packet, 0) : -1;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("opcode", opcode);
            row.put("opcodeName", sendOpcodeName(opcode));
            row.put("length", packet.length);
            if (opcode == 0x0A && packet.length > 2) {
                row.put("serverId", unsignedByte(packet, 2));
            }
            packets.add(row);
            if (opcode == 0x0A && packet.length > 2 && unsignedByte(packet, 2) == 0xFF) {
                sawEnd = true;
            } else if (opcode == 0x1A) {
                sawLastConnectedWorld = true;
            } else if (opcode == 0x1B) {
                sawRecommendedWorld = true;
            }
            if (sawEnd && sawLastConnectedWorld && sawRecommendedWorld) {
                break;
            }
        }
        result.put("packets", packets);
        result.put("received", packets.size());
        result.put("sawEndOfServerList", sawEnd);
        result.put("sawLastConnectedWorld", sawLastConnectedWorld);
        result.put("sawRecommendedWorld", sawRecommendedWorld);
        return result;
    }

    private Map<String, Object> requestCharList(Socket socket, AgentMapleCrypto.MapleSession session,
                                                int world, int channel) throws Exception {
        socket.getOutputStream().write(AgentMapleCrypto.encryptClientPacket(
                AgentLoginPacketFactory.charListRequestBody(world, channel), session));
        socket.getOutputStream().flush();
        byte[] packet = readEncryptedPacket(socket, session);
        int opcode = packet.length >= 2 ? unsignedShortLE(packet, 0) : -1;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("opcode", opcode);
        result.put("opcodeName", sendOpcodeName(opcode));
        result.put("length", packet.length);
        result.put("world", world);
        result.put("channel", channel);
        if (opcode == 0x0B && packet.length > 3) {
            result.put("status", unsignedByte(packet, 2));
            result.put("characterCount", unsignedByte(packet, 3));
        }
        return result;
    }

    private byte[] readEncryptedPacket(Socket socket, AgentMapleCrypto.MapleSession session) throws Exception {
        byte[] headerBytes = readExactly(socket, 4);
        int header = AgentMapleCrypto.readIntBE(headerBytes);
        int packetLength = AgentMapleCrypto.packetLengthFromHeader(header);
        byte[] encryptedResponse = readExactly(socket, packetLength);
        return AgentMapleCrypto.decryptServerPacket(encryptedResponse, session);
    }

    private String sendOpcodeName(int opcode) {
        return switch (opcode) {
            case 0x00 -> "LOGIN_STATUS";
            case 0x0A -> "SERVERLIST";
            case 0x0B -> "CHARLIST";
            case 0x0C -> "SERVER_IP";
            case 0x11 -> "PING";
            case 0x1A -> "LAST_CONNECTED_WORLD";
            case 0x1B -> "RECOMMENDED_WORLD_MESSAGE";
            case 0x1C -> "CHECK_SPW_RESULT";
            case 0x7D -> "SET_FIELD";
            case 0xA0 -> "SPAWN_PLAYER";
            case 0xA1 -> "REMOVE_PLAYER_FROM_MAP";
            case 0xA2 -> "CHATTEXT";
            case 0xB9 -> "MOVE_PLAYER";
            case 0xEC -> "SPAWN_MONSTER";
            case 0xED -> "KILL_MONSTER";
            case 0xEE -> "SPAWN_MONSTER_CONTROL";
            case 0xEF -> "MOVE_MONSTER";
            case 0x101 -> "SPAWN_NPC";
            case 0x102 -> "REMOVE_NPC";
            case 0x103 -> "SPAWN_NPC_REQUEST_CONTROLLER";
            case 0x10C -> "DROP_ITEM_FROM_MAPOBJECT";
            case 0x10D -> "REMOVE_ITEM_FROM_MAP";
            case 0x130 -> "NPC_TALK";
            default -> "UNKNOWN";
        };
    }

    private void closeLiveConnection(int profileId) {
        LiveConnection connection = liveConnections.remove(profileId);
        if (connection == null) {
            return;
        }
        connection.stop();
        try {
            connection.socket.close();
        } catch (Exception ignored) {
            // best-effort close during release
        }
    }

    private void startChannelPump(LiveConnection connection) {
        packetPumpExecutor.submit(() -> {
            connection.addObservation(Map.of(
                    "event", "CHANNEL_CONNECTED",
                    "host", connection.host,
                    "port", connection.port,
                    "at", Instant.now().toString()
            ));
            logAction(connection.profileId, connection.virtualSession.sessionId(), "VIRTUAL_CLIENT_CHANNEL_PUMP", "OK",
                    connection.virtualSession.world(), connection.virtualSession.channel(), connection.virtualSession.mapId(),
                    null, null, "Channel packet pump started", connection.snapshot());
            long lastHeartbeat = 0;
            while (connection.running.get() && !connection.socket.isClosed()) {
                try {
                    byte[] packet = readEncryptedPacket(connection.socket, connection.session);
                    int opcode = packet.length >= 2 ? unsignedShortLE(packet, 0) : -1;
                    Map<String, Object> observation = new LinkedHashMap<>();
                    observation.put("opcode", opcode);
                    observation.put("opcodeName", sendOpcodeName(opcode));
                    observation.put("length", packet.length);
                    observation.put("at", Instant.now().toString());
                    AgentChannelPacketObserver.observe(packet, connection.characterId())
                            .ifPresent(event -> {
                                observation.put("observed", event);
                                connection.applyObservedEvent(event);
                                syncObservedSession(connection);
                                resolveSpawnPosition(connection, event).ifPresent(spawn -> {
                                    observation.put("spawnPosition", spawn);
                                    connection.applySpawnPosition(spawn);
                                });
                            });
                    if (opcode == 0x11) {
                        connection.sendClientPacket(AgentLoginPacketFactory.pongBody());
                        observation.put("responded", "PONG");
                    }
                    connection.addObservation(observation);
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= 5_000) {
                        updateSession(connection.virtualSession, "CHANNEL_CONNECTED", "Virtual client channel packet pump is active");
                        lastHeartbeat = now;
                    }
                    maybeSendHeldManualMovementStep(connection);
                } catch (SocketTimeoutException ignored) {
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= 5_000) {
                        updateSession(connection.virtualSession, "CHANNEL_CONNECTED", "Virtual client channel packet pump is idle");
                        lastHeartbeat = now;
                    }
                    maybeSendHeldManualMovementStep(connection);
                } catch (Exception exception) {
                    if (connection.running.get()) {
                        connection.addObservation(Map.of(
                                "event", "CHANNEL_DISCONNECTED",
                                "error", exception.getClass().getSimpleName(),
                                "detail", String.valueOf(exception.getMessage()),
                                "at", Instant.now().toString()
                        ));
                        updateSession(connection.virtualSession.withState(VirtualClientState.FAILED),
                                "FAILED", "Virtual client channel packet pump disconnected: " + exception.getClass().getSimpleName());
                        logAction(connection.profileId, connection.virtualSession.sessionId(), "VIRTUAL_CLIENT_CHANNEL_PUMP", "FAILED",
                                connection.virtualSession.world(), connection.virtualSession.channel(), connection.virtualSession.mapId(),
                                null, null, "Channel packet pump disconnected", connection.snapshot());
                    }
                    liveConnections.remove(connection.profileId, connection);
                    break;
                }
            }
        });
    }

    private Map<String, Object> maybeExecuteDecisionIntent(LiveConnection connection, Map<String, Object> decision) {
        String intent = String.valueOf(decision.getOrDefault("intent", "IDLE")).toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "intent-execution-step");
        result.put("intent", intent);
        Map<String, Object> sustain = maybeAutoSustain(connection);
        if (Boolean.TRUE.equals(sustain.get("sent")) || sustain.containsKey("blocked")) {
            return sustain;
        }
        if ("NPC".equals(intent)) {
            return maybeOpenNpc(connection, objectMap(decision.get("target")));
        }
        if ("LOOT".equals(intent)) {
            return maybePickupDrop(connection, objectMap(decision.get("target")));
        }
        if ("WAIT".equals(intent)) {
            return maybeSendSocialIdle(connection, decision);
        }
        if ("SAY".equals(intent)) {
            return maybeSendChat(connection, objectMap(decision.get("target")));
        }
        if ("USE_ITEM".equals(intent) || "USE_CHAIR".equals(intent) || "CHAIR".equals(intent)) {
            return maybeUseInventoryItem(connection, intent, objectMap(decision.get("target")));
        }
        if ("EQUIP".equals(intent)) {
            result.put("sent", false);
            result.put("blocked", "Equip item packets are not calibrated yet; item usage/chair/potion packets are supported first.");
            return result;
        }
        if ("ATTACK".equals(intent)) {
            return combatPreview(connection, objectMap(decision.get("target")));
        }
        result.put("sent", false);
        result.put("reason", "No live packet executor is registered for this intent yet.");
        return result;
    }

    private Map<String, Object> executeManualAction(LiveConnection connection, String action, Map<String, Object> payload) {
        return switch (action) {
            case "CHAT", "SAY" -> maybeSendChat(connection, Map.of("text", String.valueOf(payload.getOrDefault("text", payload.getOrDefault("message", "")))));
            case "FACE", "EMOTE", "FACE_EXPRESSION" -> sendManualFaceExpression(connection, numeric(payload.getOrDefault("emote", payload.get("expression")), 2));
            case "NPC", "NPC_OPEN" -> maybeOpenNpc(connection, targetOrNearest(payload, connection.visibleNpcs, "npc"));
            case "NPC_NEXT" -> sendManualNpcContinue(connection, numeric(payload.get("messageType"), 0), 1, numeric(payload.get("selection"), 0));
            case "NPC_SELECT", "NPC_OPTION" -> sendManualNpcContinue(connection, numeric(payload.get("messageType"), 0), numeric(payload.get("action"), 1), numeric(payload.get("selection"), 0));
            case "PICKUP", "LOOT" -> maybePickupDrop(connection, targetOrNearest(payload, connection.visibleDrops, "drop"));
            case "USE_ITEM", "POTION" -> maybeUseInventoryItem(connection, "USE_ITEM", payload);
            case "USE_CHAIR", "CHAIR" -> maybeUseInventoryItem(connection, "USE_CHAIR", payload);
            case "PORTAL", "CHANGE_MAP" -> sendManualPortal(connection, payload, false);
            case "SPECIAL_PORTAL", "CHANGE_MAP_SPECIAL" -> sendManualPortal(connection, payload, true);
            case "ATTACK", "BASIC_ATTACK", "SWING" -> combatPreview(connection, targetOrNearest(payload, connection.visibleMonsters, "monster"));
            default -> blockedManualAction(action, "Unsupported manual action. Try CHAT, FACE_EXPRESSION, NPC_OPEN, NPC_SELECT, PICKUP, USE_ITEM, USE_CHAIR, PORTAL, or SPECIAL_PORTAL.");
        };
    }

    private Map<String, Object> targetOrNearest(Map<String, Object> payload, Map<Integer, Map<String, Object>> visible, String type) {
        if (numeric(payload.get("objectId"), 0) > 0) {
            return payload;
        }
        Optional<Map<String, Object>> nearest = visible.values().stream().findFirst().map(LinkedHashMap::new);
        Map<String, Object> result = nearest.orElseGet(LinkedHashMap::new);
        if (result.isEmpty()) {
            result.put("missing", "No visible " + type + " object is currently cached.");
        }
        return result;
    }

    private Map<String, Object> sendManualFaceExpression(LiveConnection connection, int emote) {
        Map<String, Object> result = AgentActionPacketFactory.describeFaceExpression(emote);
        result.put("kind", "manual-face-expression");
        result.put("enabled", sendSocialPackets);
        if (!sendSocialPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_SOCIAL_PACKETS=true for local expression packet testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        try {
            byte[] body = AgentActionPacketFactory.faceExpressionBody(emote);
            connection.sendClientPacket(body);
            connection.lastSocialAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "MANUAL_FACE_EXPRESSION_SENT",
                    "emote", emote,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> sendManualNpcContinue(LiveConnection connection, int messageType, int action, int selection) {
        Map<String, Object> result = AgentNpcPacketFactory.describeContinue(messageType, action, selection);
        result.put("kind", "manual-npc-continue");
        result.put("enabled", sendNpcPackets);
        if (!sendNpcPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_NPC_PACKETS=true for local NPC option packet testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        try {
            byte[] body = AgentNpcPacketFactory.continueBody(messageType, action, selection);
            connection.sendClientPacket(body);
            connection.lastNpcAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "MANUAL_NPC_CONTINUE_SENT",
                    "messageType", messageType,
                    "action", action,
                    "selection", selection,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> sendManualPortal(LiveConnection connection, Map<String, Object> payload, boolean special) {
        String portalName = String.valueOf(payload.getOrDefault("portalName", payload.getOrDefault("name", ""))).strip();
        if (portalName.isBlank() && !special) {
            portalName = nearestPortalName(connection).orElse("");
        }
        Map<String, Object> result = special
                ? AgentPortalPacketFactory.describeSpecialPortal(portalName)
                : AgentPortalPacketFactory.describeNormalPortal(portalName);
        result.put("kind", special ? "manual-special-portal" : "manual-portal");
        result.put("enabled", sendMovementPackets);
        if (portalName.isBlank()) {
            result.put("sent", false);
            result.put("blocked", "Portal action requires portalName or name, and no nearby portal was observed.");
            return result;
        }
        result.put("selectedPortalName", portalName);
        if (!sendMovementPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_MOVEMENT_PACKETS=true for local portal packet testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        try {
            byte[] body = special
                    ? AgentPortalPacketFactory.specialPortalBody(portalName)
                    : AgentPortalPacketFactory.normalPortalBody(portalName);
            connection.sendClientPacket(body);
            connection.lastPortalAtMillis = now;
            connection.addObservation(Map.of(
                    "event", special ? "MANUAL_SPECIAL_PORTAL_SENT" : "MANUAL_PORTAL_SENT",
                    "portalName", portalName,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> blockedManualAction(String action, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "manual-action");
        result.put("action", action);
        result.put("sent", false);
        result.put("blocked", reason);
        return result;
    }

    private Map<String, Object> maybeOpenNpc(LiveConnection connection, Map<String, Object> target) {
        AgentNpcInteractionSystem.NpcPlan plan = npcInteractionSystem.plan(new AgentNpcInteractionSystem.NpcContext(
                target,
                connection.observedX,
                connection.observedY,
                connection.activeNpcConversation,
                connection.activeNpcMessageType,
                connection.lastNpcAtMillis,
                System.currentTimeMillis()
        ));
        Map<String, Object> result = new LinkedHashMap<>(plan.detail());
        result.put("kind", "npc-interaction-step");
        result.put("action", plan.action().name());
        result.put("enabled", sendNpcPackets);
        if (!plan.executable()) {
            result.put("sent", false);
            return result;
        }
        if (!sendNpcPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_NPC_PACKETS=true only for local NPC packet-shape testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        try {
            byte[] body = switch (plan.action()) {
                case OPEN -> AgentNpcPacketFactory.openNpcBody(plan.objectId());
                case CONTINUE -> AgentNpcPacketFactory.continueBody(plan.messageType(), plan.continueAction(), plan.selection());
                case TEXT -> AgentNpcPacketFactory.textInputBody(plan.messageType(), plan.continueAction(), plan.text());
                case NONE -> new byte[0];
            };
            if (body.length == 0) {
                result.put("sent", false);
                result.put("blocked", "NPC plan produced no packet body.");
                return result;
            }
            connection.sendClientPacket(body);
            connection.lastNpcAtMillis = now;
            if (plan.action() == AgentNpcInteractionSystem.NpcAction.CONTINUE
                    || plan.action() == AgentNpcInteractionSystem.NpcAction.TEXT) {
                connection.activeNpcConversation = false;
            }
            connection.addObservation(Map.of(
                    "event", "NPC_" + plan.action().name() + "_SENT",
                    "objectId", plan.objectId(),
                    "npcId", plan.npcId() == null ? 0 : plan.npcId(),
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
            connection.addObservation(Map.of(
                    "event", "NPC_" + plan.action().name() + "_FAILED",
                    "objectId", plan.objectId(),
                    "error", exception.getClass().getSimpleName(),
                    "detail", String.valueOf(exception.getMessage()),
                    "at", Instant.now().toString()
            ));
        }
        return result;
    }

    private Map<String, Object> maybePickupDrop(LiveConnection connection, Map<String, Object> target) {
        int objectId = numeric(target.get("objectId"), 0);
        int x = connection.observedX == null ? numeric(target.get("x"), 0) : connection.observedX;
        int y = connection.observedY == null ? numeric(target.get("y"), 0) : connection.observedY;
        Map<String, Object> result = AgentActionPacketFactory.describeItemPickup(objectId, x, y);
        result.put("kind", "loot-pickup-step");
        result.put("enabled", sendLootPackets);
        if (objectId <= 0) {
            result.put("sent", false);
            result.put("blocked", "Decision target has no visible drop object id.");
            return result;
        }
        if (connection.observedX == null || connection.observedY == null) {
            result.put("sent", false);
            result.put("blocked", "No observed character x/y yet, so pickup range cannot be verified.");
            return result;
        }
        Optional<Integer> targetX = integer(target.get("x"));
        Optional<Integer> targetY = integer(target.get("y"));
        if (targetX.isPresent() && targetY.isPresent()) {
            int distanceSquared = distanceSquared(connection.observedX, connection.observedY, targetX.get(), targetY.get());
            result.put("targetX", targetX.get());
            result.put("targetY", targetY.get());
            result.put("distanceSquared", distanceSquared);
            if (distanceSquared > 120 * 120) {
                result.put("sent", false);
                result.put("blocked", "Drop is not close enough; local approach should run first.");
                return result;
            }
        }
        if (!sendLootPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_LOOT_PACKETS=true only for local pickup packet-shape testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        if (connection.lastLootAtMillis > 0 && now - connection.lastLootAtMillis < 400) {
            result.put("sent", false);
            result.put("throttled", true);
            result.put("lastSentAt", Instant.ofEpochMilli(connection.lastLootAtMillis).toString());
            return result;
        }
        try {
            byte[] body = AgentActionPacketFactory.itemPickupBody(objectId, x, y);
            connection.sendClientPacket(body);
            connection.lastLootAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "LOOT_PICKUP_SENT",
                    "objectId", objectId,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> maybeSendSocialIdle(LiveConnection connection, Map<String, Object> decision) {
        Map<String, Object> result = AgentActionPacketFactory.describeFaceExpression(2);
        result.put("kind", "social-idle-step");
        result.put("enabled", sendSocialPackets);
        result.put("decisionReason", decision.get("reason"));
        if (!sendSocialPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_SOCIAL_PACKETS=true only for local expression/chat packet-shape testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        if (connection.lastSocialAtMillis > 0 && now - connection.lastSocialAtMillis < 10_000) {
            result.put("sent", false);
            result.put("throttled", true);
            result.put("lastSentAt", Instant.ofEpochMilli(connection.lastSocialAtMillis).toString());
            return result;
        }
        try {
            int emote = 2 + (int) ((now / 10_000) % 5);
            byte[] body = AgentActionPacketFactory.faceExpressionBody(emote);
            connection.sendClientPacket(body);
            connection.lastSocialAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "FACE_EXPRESSION_SENT",
                    "emote", emote,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.clear();
            result.putAll(AgentActionPacketFactory.describeFaceExpression(emote));
            result.put("kind", "social-idle-step");
            result.put("enabled", true);
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> maybeSendChat(LiveConnection connection, Map<String, Object> target) {
        String text = String.valueOf(target.getOrDefault("text", "")).strip();
        Map<String, Object> result = AgentActionPacketFactory.describeGeneralChat(text, 0);
        result.put("kind", "chat-step");
        result.put("enabled", sendSocialPackets);
        if (text.isBlank()) {
            result.put("sent", false);
            result.put("blocked", "SAY intent has no text.");
            return result;
        }
        if (text.length() > 90) {
            text = text.substring(0, 90);
            result.clear();
            result.putAll(AgentActionPacketFactory.describeGeneralChat(text, 0));
            result.put("kind", "chat-step");
            result.put("enabled", sendSocialPackets);
            result.put("truncated", true);
        }
        if (!sendSocialPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_SOCIAL_PACKETS=true only for local chat packet-shape testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        if (connection.lastSocialAtMillis > 0 && now - connection.lastSocialAtMillis < 10_000) {
            result.put("sent", false);
            result.put("throttled", true);
            result.put("lastSentAt", Instant.ofEpochMilli(connection.lastSocialAtMillis).toString());
            return result;
        }
        try {
            byte[] body = AgentActionPacketFactory.generalChatBody(text, 0);
            connection.sendClientPacket(body);
            connection.lastSocialAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "CHAT_SENT",
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Optional<String> nearestPortalName(LiveConnection connection) {
        Integer mapId = connection.observedMapId == null ? connection.virtualSession.mapId() : connection.observedMapId;
        if (mapId == null || connection.observedX == null || connection.observedY == null) {
            return Optional.empty();
        }
        try {
            AgentMapGeometry geometry = geometryRepository.load(mapId);
            return geometry.portals().stream()
                    .filter(portal -> portal.name() != null && !portal.name().isBlank())
                    .min(Comparator.comparingLong(portal -> distanceSquared(
                            connection.observedX, connection.observedY, portal.x(), portal.y())))
                    .map(AgentMapGeometry.Portal::name);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Map<String, Object> maybeAutoSustain(LiveConnection connection) {
        AgentSustainSystem.SustainPlan plan = sustainSystem.plan(new AgentSustainSystem.SustainContext(
                sustainEnabled,
                Optional.ofNullable(connection.observedHp),
                sustainHpThreshold,
                sustainHpItemId,
                sustainHpItemSlot,
                sustainVirtualDebtAllowed,
                connection.lastInventoryAtMillis,
                System.currentTimeMillis()
        ));
        Map<String, Object> result = new LinkedHashMap<>(plan.detail());
        result.put("kind", "auto-sustain-step");
        result.put("enabled", sustainEnabled && sendInventoryPackets);
        if (plan.action() == AgentSustainSystem.SustainAction.NONE) {
            result.put("sent", false);
            return result;
        }
        if (!sendInventoryPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_INVENTORY_PACKETS=true and configure sustain hp item slot for live sustain testing.");
            return result;
        }
        try {
            byte[] body = AgentActionPacketFactory.useItemBody(plan.slot(), plan.itemId());
            connection.sendClientPacket(body);
            long now = System.currentTimeMillis();
            connection.lastInventoryAtMillis = now;
            connection.addObservation(Map.of(
                    "event", "AUTO_SUSTAIN_ITEM_SENT",
                    "itemId", plan.itemId(),
                    "slot", plan.slot(),
                    "observedHp", connection.observedHp == null ? 0 : connection.observedHp,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> maybeUseInventoryItem(LiveConnection connection, String intent, Map<String, Object> target) {
        int itemId = numeric(target.get("itemId"), 0);
        int slot = numeric(target.get("slot"), 0);
        boolean chair = "USE_CHAIR".equals(intent) || "CHAIR".equals(intent) || itemId / 10_000 == 301;
        Map<String, Object> result = chair
                ? AgentActionPacketFactory.describeUseChair(itemId)
                : AgentActionPacketFactory.describeUseItem(slot, itemId);
        result.put("kind", chair ? "chair-use-step" : "item-use-step");
        result.put("enabled", sendInventoryPackets);
        if (itemId <= 0) {
            result.put("sent", false);
            result.put("blocked", "Inventory intent has no item id.");
            return result;
        }
        if (!chair && slot <= 0) {
            result.put("sent", false);
            result.put("blocked", "USE_ITEM requires a positive inventory slot. Use a card/goal parameter such as {\"slot\":1,\"itemId\":2000000}.");
            return result;
        }
        if (!sendInventoryPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_INVENTORY_PACKETS=true only for local item/chair packet-shape testing.");
            return result;
        }
        long now = System.currentTimeMillis();
        if (connection.lastInventoryAtMillis > 0 && now - connection.lastInventoryAtMillis < 1_000) {
            result.put("sent", false);
            result.put("throttled", true);
            result.put("lastSentAt", Instant.ofEpochMilli(connection.lastInventoryAtMillis).toString());
            return result;
        }
        try {
            byte[] body = chair
                    ? AgentActionPacketFactory.useChairBody(itemId)
                    : AgentActionPacketFactory.useItemBody(slot, itemId);
            connection.sendClientPacket(body);
            connection.lastInventoryAtMillis = now;
            connection.addObservation(Map.of(
                    "event", chair ? "CHAIR_USE_SENT" : "ITEM_USE_SENT",
                    "itemId", itemId,
                    "slot", slot,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> combatPreview(LiveConnection connection, Map<String, Object> target) {
        int stance = connection.observedStance == null ? 4 : connection.observedStance;
        AgentCombatSystem.CombatPlan plan = combatSystem.plan(new AgentCombatSystem.CombatContext(
                target,
                connection.observedX,
                connection.observedY,
                stance,
                connection.lastCombatAtMillis,
                System.currentTimeMillis()
        ));
        Map<String, Object> result = new LinkedHashMap<>(plan.detail());
        result.put("kind", "combat-execution-step");
        result.put("enabled", sendCombatPackets);
        result.put("observedX", connection.observedX);
        result.put("observedY", connection.observedY);
        result.put("action", plan.action().name());
        if (plan.action() == AgentCombatSystem.CombatAction.RETREAT) {
            result.put("sent", false);
            int movementDirection = plan.direction() == 0 ? -1 : 1;
            connection.lastAutonomousIntent = AgentPhysicsSystem.MovementIntent.walk(movementDirection);
            result.put("retreatMovementDirection", movementDirection);
            result.put("next", "Kite retreat is now delegated to the 50ms physics movement loop before attacking.");
            return result;
        }
        if (!plan.executable()) {
            result.put("sent", false);
            return result;
        }
        if (!sendCombatPackets) {
            result.put("sent", false);
            result.put("next", "Set AGENT_CLIENT_SEND_COMBAT_PACKETS=true only for local close-range packet calibration.");
            return result;
        }
        try {
            byte[] body = AgentCombatPacketFactory.basicCloseRangeAttackBody(plan.objectId(), plan.attackX(), plan.attackY(),
                    plan.stance(), plan.direction(), 16, 5, plan.damage());
            connection.sendClientPacket(body);
            long now = System.currentTimeMillis();
            connection.lastCombatAtMillis = now;
            maybeNudgeControlledMonsterAfterHit(connection, plan.objectId(), plan.direction(), now);
            connection.addObservation(Map.of(
                    "event", "BASIC_ATTACK_SENT",
                    "objectId", plan.objectId(),
                    "x", plan.attackX(),
                    "y", plan.attackY(),
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
            result.put("sent", true);
            result.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            result.put("sent", false);
            result.put("error", exception.getClass().getSimpleName());
            result.put("detail", String.valueOf(exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> maybeSendManualMovementStep(LiveConnection connection, String key) {
        int x = connection.observedX == null ? 0 : connection.observedX;
        int y = connection.observedY == null ? 0 : connection.observedY;
        int foothold = connection.observedFoothold == null ? 0 : connection.observedFoothold;
        int stance = connection.observedStance == null ? 0 : connection.observedStance;
        int mapId = connection.observedMapId == null ? connection.virtualSession.mapId() : connection.observedMapId;
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("kind", "manual-movement-step");
        probe.put("enabled", sendMovementPackets);
        probe.put("key", key);
        probe.put("runtime", "AgentMovementRuntimeSystem");
        probe.put("mapId", mapId);
        probe.put("x", x);
        probe.put("y", y);
        probe.put("foothold", foothold);
        probe.put("stance", stance);
        if (!sendMovementPackets) {
            probe.put("sent", false);
            probe.put("next", "Set AGENT_CLIENT_SEND_MOVEMENT_PACKETS=true in agent-client/.env for manual movement packet tests.");
            return probe;
        }
        if (connection.observedX == null || connection.observedY == null) {
            probe.put("sent", false);
            probe.put("blocked", "No observed x/y yet. Wait for the channel packet pump to capture spawn or movement state.");
            return probe;
        }
        long now = System.currentTimeMillis();
        if (connection.lastMovementAtMillis > 0 && now - connection.lastMovementAtMillis < AgentPhysicsSystem.TICK_MILLIS) {
            probe.put("sent", false);
            probe.put("throttled", true);
            probe.put("lastSentAt", Instant.ofEpochMilli(connection.lastMovementAtMillis).toString());
            return probe;
        }
        AgentPhysicsSystem.MovementIntent intent = movementRuntimeSystem.manualIntent(key, connection.probeDirection);
        if (intent.equals(AgentPhysicsSystem.MovementIntent.idle())) {
            probe.put("sent", false);
            probe.put("blocked", "Unsupported manual movement key: " + key);
            return probe;
        }
        try {
            AgentMovementRuntimeSystem.RuntimeStep step = movementRuntimeSystem.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                    connection.profileId,
                    connection.characterId,
                    mapId,
                    x,
                    y,
                    foothold,
                    connection.probeDirection,
                    Optional.ofNullable(connection.motionState),
                    intent
            ));
            probe.putAll(step.detail());
            connection.sendClientPacket(step.body());
            connection.lastMovementAtMillis = now;
            connection.applyRuntimeMovement(step.physicsStep());
            connection.addObservation(Map.of(
                    "event", "MANUAL_MOVEMENT_SENT",
                    "key", key,
                    "physicsEvent", step.physicsStep().event(),
                    "x", step.physicsStep().current().x(),
                    "y", step.physicsStep().current().y(),
                    "bodyLength", step.body().length,
                    "at", Instant.now().toString()
            ));
            probe.put("sent", true);
            probe.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            probe.put("sent", false);
            probe.put("error", exception.getClass().getSimpleName());
            probe.put("detail", String.valueOf(exception.getMessage()));
        }
        return probe;
    }

    private void maybeSendHeldManualMovementStep(LiveConnection connection) {
        if (connection.manualKeys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (connection.lastManualMovementAtMillis > 0 && now - connection.lastManualMovementAtMillis < AgentPhysicsSystem.TICK_MILLIS) {
            return;
        }
        String key = manualMovementPriority(connection);
        if (key.isBlank()) {
            return;
        }
        Map<String, Object> step = maybeSendManualMovementStep(connection, key);
        connection.lastManualMovementAtMillis = now;
        if (Boolean.TRUE.equals(step.get("sent"))) {
            connection.addObservation(Map.of(
                    "event", "MANUAL_HELD_KEY_TICK",
                    "key", key,
                    "activeKeys", new ArrayList<>(connection.manualKeys),
                    "at", Instant.now().toString()
            ));
        }
    }

    private String manualMovementPriority(LiveConnection connection) {
        List<String> priority = List.of("JUMP", "UP", "DOWN", "LEFT", "RIGHT");
        for (String key : priority) {
            if (connection.manualKeys.contains(key)) {
                return key;
            }
        }
        return connection.manualKeys.stream().findFirst().orElse("");
    }

    private Map<String, Object> maybeSendNavigationStep(LiveConnection connection, Map<String, Object> decision) {
        int x = connection.observedX == null ? 0 : connection.observedX;
        int y = connection.observedY == null ? 0 : connection.observedY;
        int foothold = connection.observedFoothold == null ? 0 : connection.observedFoothold;
        int stance = connection.observedStance == null ? 0 : connection.observedStance;
        int mapId = connection.observedMapId == null ? connection.virtualSession.mapId() : connection.observedMapId;
        Map<String, Object> probe = AgentMovementPacketFactory.describeAbsoluteMove(x, y, foothold, stance, 0);
        probe.put("enabled", sendMovementPackets);
        probe.put("kind", "navigation-execution-step");
        probe.put("mapId", mapId);
        probe.put("hasObservedPosition", connection.observedX != null && connection.observedY != null);
        if (connection.activeNpcConversation) {
            probe.putAll(suppressMovementDuringNpc(connection, "navigation"));
            return probe;
        }
        Optional<Integer> targetMapId = decisionTargetMap(decision).or(() -> activeTargetMap(connection.profileId));
        targetMapId.ifPresent(target -> probe.put("targetMapId", target));
        Map<String, Object> decisionTarget = objectMap(decision.get("target"));
        Optional<Integer> localTargetX = Optional.empty();
        Optional<Integer> localTargetY = Optional.empty();
        String localTargetLabel = "";
        if (targetMapId.isEmpty() && !decisionTarget.isEmpty()) {
            localTargetX = integer(decisionTarget.get("x"));
            localTargetY = integer(decisionTarget.get("y"));
            localTargetLabel = localTargetLabel(decision, decisionTarget);
            localTargetX.ifPresent(value -> probe.put("localTargetX", value));
            localTargetY.ifPresent(value -> probe.put("localTargetY", value));
            if (!localTargetLabel.isBlank()) {
                probe.put("localTargetLabel", localTargetLabel);
            }
        }
        if (!sendMovementPackets) {
            probe.put("sent", false);
            probe.put("next", "Set AGENT_CLIENT_SEND_MOVEMENT_PACKETS=true only for local packet-shape testing; navigation execution is still guarded.");
            return probe;
        }
        if (connection.observedX == null || connection.observedY == null) {
            probe.put("sent", false);
            probe.put("blocked", "No observed x/y yet. Wait for SET_FIELD with explicit position or the physics/observation system to initialize spawn coordinates.");
            return probe;
        }

        long now = System.currentTimeMillis();
        if (connection.lastMovementAtMillis > 0 && now - connection.lastMovementAtMillis < AgentPhysicsSystem.TICK_MILLIS) {
            probe.put("sent", false);
            probe.put("throttled", true);
            probe.put("lastSentAt", Instant.ofEpochMilli(connection.lastMovementAtMillis).toString());
            return probe;
        }

        Optional<Map<String, Object>> localApproach = maybeLocalApproach(connection, decision, mapId, now);
        if (localApproach.isPresent()) {
            return localApproach.get();
        }

        try {
            AgentNavigationExecutionSystem.NavigationStep step = navigationExecutionSystem.nextStep(
                    new AgentNavigationExecutionSystem.NavigationContext(
                            connection.profileId,
                            connection.characterId,
                            mapId,
                            x,
                            y,
                            foothold,
                            stance,
                            connection.probeOriginX,
                            connection.probeDirection,
                            targetMapId.orElse(null),
                            localTargetX.orElse(null),
                            localTargetY.orElse(null),
                            localTargetLabel
                    )
            );
            probe.putAll(step.describe());
            if (step.blocked()) {
                connection.lastAutonomousIntent = null;
                probe.put("sent", false);
                probe.put("blocked", step.target());
                connection.addObservation(Map.of(
                        "event", "NAVIGATION_ROUTE_BLOCKED",
                        "mode", step.mode(),
                        "target", step.target(),
                        "at", Instant.now().toString()
                ));
                return probe;
            }
            if (step.requiresPortalEntry()) {
                connection.lastAutonomousIntent = null;
                Optional<String> portalName = step.portalName();
                if (portalName.isEmpty()) {
                    probe.put("sent", false);
                    probe.put("blocked", "Navigation reached a portal target but the portal name was missing.");
                    return probe;
                }
                if (connection.lastPortalAtMillis > 0 && now - connection.lastPortalAtMillis < 1_000) {
                    probe.put("sent", false);
                    probe.put("portalThrottled", true);
                    probe.put("lastPortalAt", Instant.ofEpochMilli(connection.lastPortalAtMillis).toString());
                    return probe;
                }
                byte[] body = AgentPortalPacketFactory.normalPortalBody(portalName.get());
                connection.sendClientPacket(body);
                connection.lastPortalAtMillis = now;
                connection.lastMovementAtMillis = now;
                Map<String, Object> portalPacket = AgentPortalPacketFactory.describeNormalPortal(portalName.get());
                probe.put("portalPacket", portalPacket);
                connection.addObservation(Map.of(
                        "event", "PORTAL_ENTRY_SENT",
                        "mode", step.mode(),
                        "target", step.target(),
                        "portalName", portalName.get(),
                        "bodyLength", body.length,
                        "at", Instant.now().toString()
                ));
                probe.put("sent", true);
                probe.put("sentAt", Instant.ofEpochMilli(now).toString());
                return probe;
            }
            connection.lastAutonomousIntent = null;
            byte[] body = AgentMovementPacketFactory.navigationMoveBody(
                    step.mode(),
                    x,
                    y,
                    step.x(),
                    step.y(),
                    step.foothold(),
                    step.stance(),
                    step.durationMillis()
            );
            Map<String, Object> packet = AgentMovementPacketFactory.describeNavigationMove(
                    step.mode(),
                    x,
                    y,
                    step.x(),
                    step.y(),
                    step.foothold(),
                    step.stance(),
                    step.durationMillis()
            );
            probe.put("runtime", "AgentNavigationExecutionSystem");
            probe.put("packet", packet);
            connection.sendClientPacket(body);
            connection.lastMovementAtMillis = now;
            connection.applyPredictedMovement(step);
            connection.addObservation(Map.of(
                    "event", "NAVIGATION_STEP_SENT",
                    "mode", step.mode(),
                    "target", step.target(),
                    "bodyLength", body.length,
                    "packetKind", packet.get("fragmentType"),
                    "x", step.x(),
                    "y", step.y(),
                    "at", Instant.now().toString()
            ));
            probe.put("sent", true);
            probe.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            probe.put("sent", false);
            probe.put("error", exception.getClass().getSimpleName());
            probe.put("detail", String.valueOf(exception.getMessage()));
            connection.addObservation(Map.of(
                    "event", "NAVIGATION_STEP_FAILED",
                    "error", exception.getClass().getSimpleName(),
                    "detail", String.valueOf(exception.getMessage()),
                    "at", Instant.now().toString()
            ));
        }
        return probe;
    }

    private Map<String, Object> suppressMovementDuringNpc(LiveConnection connection, String source) {
        connection.lastAutonomousIntent = null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "movement-suppressed");
        result.put("sent", false);
        result.put("source", source);
        result.put("blocked", "NPC conversation is active; movement pauses until the dialog flow advances or closes.");
        result.put("messageType", connection.activeNpcMessageType);
        return result;
    }

    private void maybeNudgeControlledMonsterAfterHit(LiveConnection connection, int objectId, int attackDirection, long now) {
        if (!sendMobMovementPackets) {
            return;
        }
        ControlledMonsterState monster = connection.controlledMonsters.get(objectId);
        if (monster == null || !monster.hasCoordinates()) {
            return;
        }
        int direction = attackDirection == 0 ? -1 : 1;
        int nextX = monster.x + (direction * 18);
        try {
            int moveId = connection.nextMobMoveId();
            byte[] body = AgentMobMovementPacketFactory.moveLifeBody(
                    monster.objectId,
                    moveId,
                    monster.x,
                    monster.y,
                    nextX,
                    monster.y,
                    monster.foothold,
                    direction >= 0 ? 2 : 3,
                    180,
                    -1
            );
            connection.sendClientPacket(body);
            monster.x = nextX;
            monster.direction = direction;
            monster.lastMovedAtMillis = now;
            connection.mergeControlledMonsterPosition(monster);
            connection.addObservation(Map.of(
                    "event", "CONTROLLED_MONSTER_HIT_NUDGE_SENT",
                    "objectId", monster.objectId,
                    "moveId", moveId,
                    "x", nextX,
                    "y", monster.y,
                    "bodyLength", body.length,
                    "at", Instant.now().toString()
            ));
        } catch (Exception exception) {
            connection.addObservation(Map.of(
                    "event", "CONTROLLED_MONSTER_HIT_NUDGE_FAILED",
                    "objectId", objectId,
                    "error", exception.getClass().getSimpleName(),
                    "detail", String.valueOf(exception.getMessage()),
                    "at", Instant.now().toString()
            ));
        }
    }

    private AgentPhysicsSystem.MovementIntent navigationIntent(AgentNavigationExecutionSystem.NavigationStep step,
                                                               int currentX, int currentY, int fallbackDirection) {
        if (step.mode() != null && step.mode().contains("CLIMB")) {
            return AgentPhysicsSystem.MovementIntent.climb(Integer.compare(step.y(), currentY));
        }
        if (step.mode() != null && step.mode().contains("JUMP")) {
            return AgentPhysicsSystem.MovementIntent.jump(Integer.compare(step.x() - currentX, 0));
        }
        if (step.mode() != null && step.mode().contains("DROP")) {
            return AgentPhysicsSystem.MovementIntent.drop(Integer.compare(step.x() - currentX, 0));
        }
        int direction = Integer.compare(step.x() - currentX, 0);
        if (direction == 0) {
            direction = step.nextDirection() == 0 ? fallbackDirection : step.nextDirection();
        }
        return AgentPhysicsSystem.MovementIntent.walk(direction);
    }

    private Optional<Map<String, Object>> maybeLocalApproach(LiveConnection connection, Map<String, Object> decision,
                                                            int mapId, long now) {
        String intent = String.valueOf(decision.getOrDefault("intent", "")).toUpperCase();
        if (!List.of("NPC", "LOOT", "ATTACK", "FOLLOW_CHARACTER").contains(intent)) {
            return Optional.empty();
        }
        Map<String, Object> target = objectMap(decision.get("target"));
        Optional<Integer> targetX = integer(target.get("x"));
        Optional<Integer> targetY = integer(target.get("y"));
        if (targetX.isEmpty() || targetY.isEmpty()) {
            return Optional.empty();
        }
        int x = connection.observedX == null ? 0 : connection.observedX;
        int y = connection.observedY == null ? 0 : connection.observedY;
        int dx = targetX.get() - x;
        int dy = targetY.get() - y;
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("enabled", sendMovementPackets);
        probe.put("kind", "local-approach-step");
        probe.put("intent", intent);
        probe.put("mapId", mapId);
        probe.put("x", x);
        probe.put("y", y);
        probe.put("targetX", targetX.get());
        probe.put("targetY", targetY.get());
        probe.put("distanceSquared", (dx * dx) + (dy * dy));
        if (Math.abs(dx) <= 35 && Math.abs(dy) <= 45) {
            connection.lastAutonomousIntent = null;
            probe.put("sent", false);
            probe.put("arrived", true);
            probe.put("reason", "Already near enough for the intent executor.");
            return Optional.of(probe);
        }
        if (Math.abs(dy) > 90) {
            probe.put("sent", false);
            probe.put("delegatedToGraph", true);
            probe.put("reason", "Target requires vertical graph navigation.");
            return Optional.empty();
        }
        int foothold = connection.observedFoothold == null ? 0 : connection.observedFoothold;
        int direction = Integer.compare(dx, 0);
        AgentPhysicsSystem.MovementIntent movementIntent = AgentPhysicsSystem.MovementIntent.walk(direction);
        connection.lastAutonomousIntent = movementIntent;
        probe.put("runtime", "AgentMovementRuntimeSystem");
        if (!sendMovementPackets) {
            probe.put("sent", false);
            probe.put("next", "Set AGENT_CLIENT_SEND_MOVEMENT_PACKETS=true to perform local approach packet tests.");
            return Optional.of(probe);
        }
        try {
            AgentMovementRuntimeSystem.RuntimeStep step = movementRuntimeSystem.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                    connection.profileId,
                    connection.characterId,
                    mapId,
                    x,
                    y,
                    foothold,
                    connection.probeDirection,
                    Optional.ofNullable(connection.motionState),
                    movementIntent
            ));
            probe.put("runtimeStep", step.detail());
            connection.sendClientPacket(step.body());
            connection.lastMovementAtMillis = now;
            connection.applyRuntimeMovement(step.physicsStep());
            connection.addObservation(Map.of(
                    "event", "LOCAL_APPROACH_SENT",
                    "intent", intent,
                    "physicsEvent", step.physicsStep().event(),
                    "x", step.physicsStep().current().x(),
                    "y", step.physicsStep().current().y(),
                    "bodyLength", step.body().length,
                    "at", Instant.now().toString()
            ));
            probe.put("sent", true);
            probe.put("sentAt", Instant.ofEpochMilli(now).toString());
        } catch (Exception exception) {
            probe.put("sent", false);
            probe.put("error", exception.getClass().getSimpleName());
            probe.put("detail", String.valueOf(exception.getMessage()));
        }
        return Optional.of(probe);
    }

    private Optional<Integer> activeTargetMap(int profileId) {
        List<Map<String, Object>> goalRows = cmsJdbc.queryForList("""
                SELECT target_map
                FROM agent_goals
                WHERE agent_profile_id=?
                  AND target_map IS NOT NULL
                  AND status IN ('ACTIVE', 'RUNNING', 'PENDING')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'PENDING' THEN 2 ELSE 3 END,
                         priority DESC, id
                LIMIT 1
                """, profileId);
        if (!goalRows.isEmpty()) {
            Object value = goalRows.getFirst().get("target_map");
            if (value instanceof Number number) {
                return Optional.of(number.intValue());
            }
        }

        List<Map<String, Object>> loadoutRows = cmsJdbc.queryForList("""
                SELECT c.config_json
                FROM agent_card_loadouts l
                JOIN agent_cards c ON c.id = l.card_id
                WHERE l.agent_profile_id=?
                  AND l.enabled=1
                  AND l.slot_key = 'active_task'
                  AND c.card_type='TASK'
                ORDER BY l.priority DESC, l.id
                LIMIT 1
                """, profileId);
        if (loadoutRows.isEmpty()) {
            return Optional.empty();
        }
        Object config = loadoutRows.getFirst().get("config_json");
        if (config == null) {
            return Optional.empty();
        }
        try {
            Map<?, ?> parsed = mapper.readValue(String.valueOf(config), Map.class);
            Object targetMap = Optional.ofNullable(parsed.get("targetMapId")).orElse(parsed.get("endpointMapId"));
            if (targetMap instanceof Number number) {
                return Optional.of(number.intValue());
            }
            if (targetMap != null && !String.valueOf(targetMap).isBlank()) {
                return Optional.of(Integer.parseInt(String.valueOf(targetMap)));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<Integer> decisionTargetMap(Map<String, Object> decision) {
        if (!"MOVE_TO_MAP".equals(String.valueOf(decision.get("intent")))) {
            return Optional.empty();
        }
        Map<String, Object> target = objectMap(decision.get("target"));
        Object mapId = target.get("mapId");
        if (mapId instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (mapId != null && !String.valueOf(mapId).isBlank()) {
            try {
                return Optional.of(Integer.parseInt(String.valueOf(mapId)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private String localTargetLabel(Map<String, Object> decision, Map<String, Object> target) {
        String intent = String.valueOf(decision.getOrDefault("intent", "TARGET")).toUpperCase();
        Object name = Optional.ofNullable(target.get("name"))
                .orElseGet(() -> Optional.ofNullable(target.get("label")).orElse(target.get("target")));
        Object id = Optional.ofNullable(target.get("npcId"))
                .orElseGet(() -> Optional.ofNullable(target.get("mobId"))
                        .orElseGet(() -> Optional.ofNullable(target.get("itemId"))
                                .orElseGet(() -> Optional.ofNullable(target.get("objectId")).orElse(target.get("id")))));
        StringBuilder label = new StringBuilder(intent);
        if (name != null && !String.valueOf(name).isBlank()) {
            label.append(" ").append(name);
        }
        if (id != null && !String.valueOf(id).isBlank()) {
            label.append(" #").append(id);
        }
        return label.toString();
    }

    private Optional<Integer> integer(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private int distanceSquared(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        return (dx * dx) + (dy * dy);
    }

    private Optional<Map<String, Object>> resolveSpawnPosition(LiveConnection connection, Map<String, Object> event) {
        if (connection.observedX != null && connection.observedY != null) {
            return Optional.empty();
        }
        Object kind = event.get("kind");
        Object mapId = event.get("mapId");
        Object spawnPoint = event.get("spawnPoint");
        if (!"WARP_TO_MAP".equals(kind) || !(mapId instanceof Number mapNumber) || !(spawnPoint instanceof Number spawnNumber)) {
            return Optional.empty();
        }
        return spawnPositionResolver.resolve(mapNumber.intValue(), spawnNumber.intValue());
    }

    private void initializeCachedLocation(LiveConnection connection, Map<String, Object> profile) {
        int mapId = numeric(profile.get("map"), connection.virtualSession.mapId());
        int spawnPoint = numeric(profile.get("spawnpoint"), 0);
        if (mapId <= 0) {
            connection.addObservation(Map.of(
                    "event", "CACHED_LOCATION_SKIPPED",
                    "detail", "Agent profile cache does not have a positive map id yet.",
                    "profileMap", mapId,
                    "at", Instant.now().toString()
            ));
            return;
        }

        connection.observedMapId = mapId;
        connection.observedSpawnPoint = spawnPoint;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("event", "CACHED_LOCATION_INITIALIZED");
        observation.put("mapId", mapId);
        observation.put("spawnPoint", spawnPoint);
        observation.put("source", "agent_profiles cached map/spawnpoint");
        spawnPositionResolver.resolve(mapId, spawnPoint).ifPresent(spawn -> {
            observation.put("spawnPosition", spawn);
            connection.applySpawnPosition(spawn);
        });
        observation.put("hasObservedPosition", connection.observedX != null && connection.observedY != null);
        observation.put("at", Instant.now().toString());
        connection.addObservation(observation);
    }

    private byte[] readExactly(Socket socket, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = socket.getInputStream().read(bytes, offset, length - offset);
            if (read < 0) {
                throw new IllegalStateException("Socket closed while reading MapleStory hello");
            }
            offset += read;
        }
        return bytes;
    }

    private Map<String, Object> parseHello(byte[] body, int helloLength) {
        Map<String, Object> protocol = new LinkedHashMap<>();
        protocol.put("mapleVersion", unsignedShortLE(body, 0));
        protocol.put("subVersion", unsignedShortLE(body, 2));
        protocol.put("locale", unsignedByte(body, 4));
        protocol.put("clientSendIv", hex(Arrays.copyOfRange(body, 5, 9)));
        protocol.put("clientReceiveIv", hex(Arrays.copyOfRange(body, 9, 13)));
        protocol.put("patchLocation", helloLength > 13 ? unsignedByte(body, 13) : null);
        protocol.put("next", "encrypted LOGIN_PASSWORD packet");
        return protocol;
    }

    private int unsignedShortLE(byte[] bytes, int offset) {
        return unsignedByte(bytes, offset) | (unsignedByte(bytes, offset + 1) << 8);
    }

    private int readIntLE(byte[] bytes, int offset) {
        return unsignedByte(bytes, offset)
                | (unsignedByte(bytes, offset + 1) << 8)
                | (unsignedByte(bytes, offset + 2) << 16)
                | (unsignedByte(bytes, offset + 3) << 24);
    }

    private int unsignedByte(byte[] bytes, int offset) {
        return bytes[offset] & 0xFF;
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> copy.put(String.valueOf(key), mapValue));
            return copy;
        }
        return Map.of();
    }

    private int channelPort(int world, int channel) {
        return channelPortBase + (world * worldPortStride) + Math.max(0, sanitizeChannel(channel) - 1);
    }

    private int sanitizeChannel(int channel) {
        return Math.max(1, channel);
    }

    private int numeric(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private record VirtualSession(long sessionId, int profileId, int characterId, int world, int channel, int mapId,
                                  VirtualClientState state, Instant touchedAt) {
        private VirtualSession withState(VirtualClientState nextState) {
            return new VirtualSession(sessionId, profileId, characterId, world, channel, mapId, nextState, Instant.now());
        }

        private VirtualSession withMap(int nextMapId) {
            return new VirtualSession(sessionId, profileId, characterId, world, channel, nextMapId, state, Instant.now());
        }
    }

    private enum VirtualClientState {
        PREPARED,
        CONNECTIVITY_READY,
        HANDSHAKE_READY,
        CREDENTIAL_REQUIRED,
        ENCRYPTED_LOGIN_READY,
        CHANNEL_CONNECTED,
        PROTOCOL_PENDING,
        STOPPED,
        FAILED;

        private String taskDescription() {
            return switch (this) {
                case CREDENTIAL_REQUIRED -> "Cosmic login hello parsed, but this agent has no Agent CMS runtime credential.";
                case ENCRYPTED_LOGIN_READY -> "Cosmic login hello parsed and encrypted LOGIN_PASSWORD frame is ready; live send is disabled unless explicitly enabled.";
                case CHANNEL_CONNECTED -> "Virtual client completed login transition and opened the selected channel socket.";
                case HANDSHAKE_READY -> "Cosmic login hello parsed and channel socket is reachable.";
                default -> name();
            };
        }

        private static VirtualClientState from(String value) {
            for (VirtualClientState state : values()) {
                if (state.name().equalsIgnoreCase(value)) {
                    return state;
                }
            }
            return PREPARED;
        }
    }

    private record RuntimeCredential(String accountName, String passwordSecret, String secretFormat) {}

    private static final class ControlledMonsterState {
        private final int objectId;
        private int mobId;
        private int originX;
        private int x;
        private int y;
        private int foothold;
        private int stance;
        private int direction = 1;
        private long lastMovedAtMillis;

        private ControlledMonsterState(int objectId, int mobId, int x, int y, int foothold, int stance) {
            this.objectId = objectId;
            this.mobId = mobId;
            this.originX = x;
            this.x = x;
            this.y = y;
            this.foothold = foothold;
            this.stance = stance;
        }

        private boolean hasCoordinates() {
            return Math.abs(x) < 10000 && Math.abs(y) < 5000;
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("objectId", objectId);
            result.put("mobId", mobId);
            result.put("originX", originX);
            result.put("x", x);
            result.put("y", y);
            result.put("foothold", foothold);
            result.put("stance", stance);
            result.put("direction", direction);
            if (lastMovedAtMillis > 0) {
                result.put("lastMovedAt", Instant.ofEpochMilli(lastMovedAtMillis).toString());
            }
            return result;
        }
    }

    private static final class LiveConnection {
        private static final int MAX_OBSERVATIONS = 30;

        private final int profileId;
        private volatile VirtualSession virtualSession;
        private final int characterId;
        private final String host;
        private final int port;
        private final Socket socket;
        private final AgentMapleCrypto.MapleSession session;
        private final Map<String, Object> protocol;
        private final Instant connectedAt;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Map<String, Object>> recentObservations = new ArrayList<>();
        private final Map<Integer, Map<String, Object>> visibleNpcs = new ConcurrentHashMap<>();
        private final Map<Integer, Map<String, Object>> visibleMonsters = new ConcurrentHashMap<>();
        private final Map<Integer, ControlledMonsterState> controlledMonsters = new ConcurrentHashMap<>();
        private final Map<Integer, Map<String, Object>> visibleDrops = new ConcurrentHashMap<>();
        private final Map<Integer, Map<String, Object>> visiblePlayers = new ConcurrentHashMap<>();
        private final java.util.Set<String> manualKeys = ConcurrentHashMap.newKeySet();
        private final List<Map<String, Object>> recentChat = new ArrayList<>();
        private volatile long lastMovementAtMillis;
        private volatile long lastManualMovementAtMillis;
        private volatile long lastPortalAtMillis;
        private volatile long lastNpcAtMillis;
        private volatile long lastLootAtMillis;
        private volatile long lastSocialAtMillis;
        private volatile long lastInventoryAtMillis;
        private volatile long lastCombatAtMillis;
        private volatile long lastMobMovementAtMillis;
        private volatile long lastDecisionAtMillis;
        private volatile long lastAutonomyRefreshAtMillis;
        private volatile boolean activeNpcConversation;
        private volatile int activeNpcMessageType;
        private volatile long lastNpcTalkAtMillis;
        private volatile boolean autonomousEnabled;
        private volatile Integer observedMapId;
        private volatile Integer observedSpawnPoint;
        private volatile Integer observedHp;
        private volatile Integer observedX;
        private volatile Integer observedY;
        private volatile Integer observedFoothold;
        private volatile Integer observedStance;
        private volatile Integer probeOriginX;
        private volatile int probeDirection = 1;
        private volatile AgentPhysicsSystem.MotionState motionState;
        private volatile AgentPhysicsSystem.MovementIntent lastAutonomousIntent;
        private volatile int mobMoveId = 1;

        private LiveConnection(int profileId, VirtualSession virtualSession, int characterId, String host, int port, Socket socket,
                               AgentMapleCrypto.MapleSession session, Map<String, Object> protocol,
                               Instant connectedAt, boolean autonomousEnabled) {
            this.profileId = profileId;
            this.virtualSession = virtualSession;
            this.characterId = characterId;
            this.host = host;
            this.port = port;
            this.socket = socket;
            this.session = session;
            this.protocol = protocol;
            this.connectedAt = connectedAt;
            this.autonomousEnabled = autonomousEnabled;
        }

        private String host() {
            return host;
        }

        private int port() {
            return port;
        }

        private int characterId() {
            return characterId;
        }

        private void stop() {
            running.set(false);
            manualKeys.clear();
            lastAutonomousIntent = null;
        }

        private synchronized void sendClientPacket(byte[] body) throws Exception {
            socket.getOutputStream().write(AgentMapleCrypto.encryptClientPacket(body, session));
            socket.getOutputStream().flush();
        }

        private synchronized void addObservation(Map<String, Object> observation) {
            recentObservations.add(new LinkedHashMap<>(observation));
            while (recentObservations.size() > MAX_OBSERVATIONS) {
                recentObservations.remove(0);
            }
        }

        private void applyObservedEvent(Map<String, Object> event) {
            applyPerceptionEvent(event);
            Object eventName = event.get("event");
            String type = eventName == null ? "" : String.valueOf(eventName);
            Object kind = event.get("kind");
            boolean selfMovementEvent = "MOVE_PLAYER".equals(type) && Boolean.TRUE.equals(event.get("self"));
            boolean ownFieldEvent = "WARP_TO_MAP".equals(kind);
            Object mapId = event.get("mapId");
            if (ownFieldEvent && mapId instanceof Number number) {
                observedMapId = number.intValue();
                motionState = null;
            }
            Object spawnPoint = event.get("spawnPoint");
            if (ownFieldEvent && spawnPoint instanceof Number number) {
                observedSpawnPoint = number.intValue();
            }
            Object hp = event.get("hp");
            if (ownFieldEvent && hp instanceof Number number) {
                observedHp = number.intValue();
            }
            Object x = event.get("x");
            Object y = event.get("y");
            if (ownFieldEvent && x instanceof Number xNumber && y instanceof Number yNumber) {
                observedX = xNumber.intValue();
                observedY = yNumber.intValue();
                if (probeOriginX == null) {
                    probeOriginX = observedX;
                }
                motionState = null;
            }
            Object movement = event.get("movement");
            if (selfMovementEvent && movement instanceof Map<?, ?> movementMap) {
                Object movementX = movementMap.get("x");
                Object movementY = movementMap.get("y");
                if (movementX instanceof Number xNumber && movementY instanceof Number yNumber) {
                    observedX = xNumber.intValue();
                    observedY = yNumber.intValue();
                    motionState = null;
                }
                Object foothold = movementMap.get("foothold");
                if (foothold instanceof Number number) {
                    observedFoothold = number.intValue();
                }
                Object stance = movementMap.get("stance");
                if (stance instanceof Number number) {
                    observedStance = number.intValue();
                }
            }
        }

        private synchronized void applyPerceptionEvent(Map<String, Object> event) {
            Object eventName = event.get("event");
            String type = eventName == null ? "" : String.valueOf(eventName);
            if ("WARP_TO_MAP".equals(event.get("kind"))) {
                visibleNpcs.clear();
                visibleMonsters.clear();
                controlledMonsters.clear();
                visibleDrops.clear();
                visiblePlayers.clear();
                recentChat.clear();
                motionState = null;
                lastAutonomousIntent = null;
                return;
            }
            switch (type) {
                case "NPC_VISIBLE" -> putVisible(visibleNpcs, event);
                case "NPC_REMOVED" -> removeVisible(visibleNpcs, event);
                case "MONSTER_VISIBLE" -> {
                    mergeMonsterVisible(event);
                    maybeTrackMonsterControl(event);
                }
                case "MONSTER_MOVED" -> {
                    mergeVisible(visibleMonsters, event);
                    updateControlledMonsterPosition(event);
                }
                case "MONSTER_REMOVED" -> {
                    objectKey(event).ifPresent(controlledMonsters::remove);
                    if (!Boolean.TRUE.equals(event.get("controlOnly"))) {
                        removeVisible(visibleMonsters, event);
                    }
                }
                case "DROP_VISIBLE" -> putVisible(visibleDrops, event);
                case "DROP_REMOVED" -> removeVisible(visibleDrops, event);
                case "PLAYER_VISIBLE" -> {
                    Object self = event.get("self");
                    if (!(self instanceof Boolean bool) || !bool) {
                        putVisible(visiblePlayers, event);
                    }
                }
                case "PLAYER_REMOVED" -> {
                    Object characterId = event.get("characterId");
                    if (characterId instanceof Number number) {
                        visiblePlayers.remove(number.intValue());
                    }
                }
                case "MOVE_PLAYER" -> {
                    Object movedCharacterId = event.get("characterId");
                    if (movedCharacterId instanceof Number number && visiblePlayers.containsKey(number.intValue())) {
                        mergeVisible(visiblePlayers, event);
                    }
                }
                case "MAP_CHAT" -> {
                    recentChat.add(new LinkedHashMap<>(event));
                    while (recentChat.size() > 20) {
                        recentChat.remove(0);
                    }
                }
                case "NPC_TALK" -> {
                    activeNpcConversation = true;
                    Object messageType = event.get("messageType");
                    if (messageType instanceof Number number) {
                        activeNpcMessageType = number.intValue();
                    }
                    lastNpcTalkAtMillis = System.currentTimeMillis();
                    addObservation(Map.of(
                            "event", "NPC_CONVERSATION_ACTIVE",
                            "messageType", activeNpcMessageType,
                            "rawLength", event.getOrDefault("rawLength", 0),
                            "at", Instant.now().toString()
                    ));
                }
                default -> {
                    // Packet is meaningful to the connection but not part of the perception cache.
                }
            }
        }

        private void putVisible(Map<Integer, Map<String, Object>> target, Map<String, Object> event) {
            objectKey(event).ifPresent(key -> target.put(key, new LinkedHashMap<>(event)));
        }

        private void mergeVisible(Map<Integer, Map<String, Object>> target, Map<String, Object> event) {
            objectKey(event).ifPresent(key -> {
                Map<String, Object> existing = target.get(key);
                if (existing == null) {
                    target.put(key, new LinkedHashMap<>(event));
                } else {
                    existing.putAll(event);
                }
            });
        }

        private void mergeMonsterVisible(Map<String, Object> event) {
            Map<String, Object> sanitized = new LinkedHashMap<>(event);
            Object foothold = sanitized.get("foothold");
            if (foothold instanceof Number number && number.intValue() < 0) {
                // Controller packets can omit fixed spawn geometry behind variable-length mob-status data.
                // Preserve the normal spawn packet's valid x/y/foothold so combat does not chase fake positions.
                sanitized.remove("x");
                sanitized.remove("y");
                sanitized.remove("foothold");
            }
            if (sanitized.containsKey("movement") || !looksLikeUsableMapCoordinate(sanitized)) {
                // Movement/control packets are parsed from variable-length payloads. Treat them as liveness
                // signals, but do not let them replace the reliable coordinates from spawn packets.
                sanitized.remove("x");
                sanitized.remove("y");
                sanitized.remove("foothold");
            }
            mergeVisible(visibleMonsters, sanitized);
        }

        private void maybeTrackMonsterControl(Map<String, Object> event) {
            Integer objectId = objectKey(event).orElse(null);
            Integer controllerMode = parseInteger(event.get("controllerMode"));
            if (objectId == null || controllerMode == null || controllerMode <= 0) {
                return;
            }
            Map<String, Object> visible = visibleMonsters.getOrDefault(objectId, Map.of());
            int x = firstInteger(event.get("x"), visible.get("x"), observedX, 0);
            int y = firstInteger(event.get("y"), visible.get("y"), observedY, 0);
            int foothold = firstInteger(event.get("foothold"), visible.get("foothold"), observedFoothold, 0);
            int mobId = firstInteger(event.get("mobId"), visible.get("mobId"), 0);
            int stance = firstInteger(event.get("stance"), visible.get("stance"), 2);
            controlledMonsters.compute(objectId, (ignored, existing) -> {
                ControlledMonsterState state = existing == null
                        ? new ControlledMonsterState(objectId, mobId, x, y, foothold, stance)
                        : existing;
                state.mobId = mobId;
                state.x = x;
                state.y = y;
                state.foothold = foothold;
                state.stance = stance;
                return state;
            });
        }

        private void updateControlledMonsterPosition(Map<String, Object> event) {
            Integer objectId = objectKey(event).orElse(null);
            if (objectId == null) {
                return;
            }
            ControlledMonsterState state = controlledMonsters.get(objectId);
            if (state == null) {
                return;
            }
            Integer x = parseInteger(event.get("x"));
            Integer y = parseInteger(event.get("y"));
            Integer foothold = parseInteger(event.get("foothold"));
            Integer stance = parseInteger(event.get("stance"));
            if (x != null && Math.abs(x) < 10000) {
                state.x = x;
            }
            if (y != null && Math.abs(y) < 5000) {
                state.y = y;
            }
            if (foothold != null) {
                state.foothold = foothold;
            }
            if (stance != null) {
                state.stance = stance;
            }
        }

        private void mergeControlledMonsterPosition(ControlledMonsterState monster) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "MONSTER_MOVED");
            event.put("objectId", monster.objectId);
            event.put("mobId", monster.mobId);
            event.put("x", monster.x);
            event.put("y", monster.y);
            event.put("foothold", monster.foothold);
            event.put("stance", monster.stance);
            mergeVisible(visibleMonsters, event);
        }

        private boolean looksLikeUsableMapCoordinate(Map<String, Object> event) {
            Integer x = parseInteger(event.get("x"));
            Integer y = parseInteger(event.get("y"));
            if (x == null || y == null) {
                return true;
            }
            return Math.abs(x) < 10000 && Math.abs(y) < 5000;
        }

        private Integer parseInteger(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private int firstInteger(Object first, Object second, Integer third, int fallback) {
            Integer value = parseInteger(first);
            if (value != null) {
                return value;
            }
            value = parseInteger(second);
            if (value != null) {
                return value;
            }
            return third == null ? fallback : third;
        }

        private int firstInteger(Object first, Object second, int fallback) {
            Integer value = parseInteger(first);
            if (value != null) {
                return value;
            }
            value = parseInteger(second);
            return value == null ? fallback : value;
        }

        private int nextMobMoveId() {
            int next = mobMoveId++;
            if (mobMoveId > Short.MAX_VALUE) {
                mobMoveId = 1;
            }
            return next;
        }

        private void removeVisible(Map<Integer, Map<String, Object>> target, Map<String, Object> event) {
            objectKey(event).ifPresent(target::remove);
        }

        private Optional<Integer> objectKey(Map<String, Object> event) {
            Object objectId = event.get("objectId");
            if (objectId instanceof Number number) {
                return Optional.of(number.intValue());
            }
            Object characterId = event.get("characterId");
            if (characterId instanceof Number number) {
                return Optional.of(number.intValue());
            }
            return Optional.empty();
        }

        private void applySpawnPosition(Map<String, Object> spawn) {
            Object x = spawn.get("x");
            Object y = spawn.get("y");
            if (x instanceof Number xNumber && y instanceof Number yNumber) {
                observedX = xNumber.intValue();
                observedY = yNumber.intValue();
                if (probeOriginX == null) {
                    probeOriginX = observedX;
                }
                motionState = null;
            }
        }

        private void applyPredictedMovement(AgentNavigationExecutionSystem.NavigationStep step) {
            observedX = step.x();
            observedY = step.y();
            observedFoothold = step.foothold();
            observedStance = step.stance();
            probeDirection = step.nextDirection();
            motionState = null;
            lastAutonomousIntent = null;
            if (probeOriginX == null) {
                probeOriginX = step.x();
            }
        }

        private void applyRuntimeMovement(AgentPhysicsSystem.PhysicsStep step) {
            AgentPhysicsSystem.MotionState state = step.current();
            motionState = state;
            observedX = state.x();
            observedY = state.y();
            observedFoothold = state.footholdId();
            observedStance = state.stance();
            probeDirection = state.direction() == 0 ? probeDirection : state.direction();
            if (probeOriginX == null) {
                probeOriginX = state.x();
            }
        }

        private synchronized Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("connected", running.get() && !socket.isClosed());
            snapshot.put("sessionId", virtualSession.sessionId());
            snapshot.put("world", virtualSession.world());
            snapshot.put("channel", virtualSession.channel());
            snapshot.put("mapId", virtualSession.mapId());
            snapshot.put("host", host);
            snapshot.put("port", port);
            snapshot.put("characterId", characterId);
            snapshot.put("connectedAt", connectedAt.toString());
            if (lastMovementAtMillis > 0) {
                snapshot.put("lastMovementAt", Instant.ofEpochMilli(lastMovementAtMillis).toString());
            }
            snapshot.put("autonomousEnabled", autonomousEnabled);
            snapshot.put("autonomousIntentActive", lastAutonomousIntent != null);
            snapshot.put("manualKeys", new ArrayList<>(manualKeys));
            if (lastPortalAtMillis > 0) {
                snapshot.put("lastPortalAt", Instant.ofEpochMilli(lastPortalAtMillis).toString());
            }
            if (lastNpcAtMillis > 0) {
                snapshot.put("lastNpcAt", Instant.ofEpochMilli(lastNpcAtMillis).toString());
            }
            if (lastNpcTalkAtMillis > 0) {
                snapshot.put("lastNpcTalkAt", Instant.ofEpochMilli(lastNpcTalkAtMillis).toString());
            }
            if (lastLootAtMillis > 0) {
                snapshot.put("lastLootAt", Instant.ofEpochMilli(lastLootAtMillis).toString());
            }
            if (lastSocialAtMillis > 0) {
                snapshot.put("lastSocialAt", Instant.ofEpochMilli(lastSocialAtMillis).toString());
            }
            if (lastInventoryAtMillis > 0) {
                snapshot.put("lastInventoryAt", Instant.ofEpochMilli(lastInventoryAtMillis).toString());
            }
            if (lastCombatAtMillis > 0) {
                snapshot.put("lastCombatAt", Instant.ofEpochMilli(lastCombatAtMillis).toString());
            }
            Map<String, Object> observed = new LinkedHashMap<>();
            if (observedMapId != null) {
                observed.put("mapId", observedMapId);
            }
            if (observedSpawnPoint != null) {
                observed.put("spawnPoint", observedSpawnPoint);
            }
            if (observedHp != null) {
                observed.put("hp", observedHp);
            }
            observed.put("activeNpcConversation", activeNpcConversation);
            observed.put("activeNpcMessageType", activeNpcMessageType);
            if (observedX != null && observedY != null) {
                observed.put("x", observedX);
                observed.put("y", observedY);
            }
            if (observedFoothold != null) {
                observed.put("foothold", observedFoothold);
            }
            if (observedStance != null) {
                observed.put("stance", observedStance);
            }
            if (probeOriginX != null) {
                observed.put("probeOriginX", probeOriginX);
                observed.put("probeDirection", probeDirection);
            }
            if (motionState != null) {
                observed.put("motionMode", motionState.mode().name());
                observed.put("velocityX", motionState.velocityX());
                observed.put("velocityY", motionState.velocityY());
                observed.put("ladderRopeIndex", motionState.ladderRopeIndex());
            }
            snapshot.put("observed", observed);
            Map<String, Object> perception = new LinkedHashMap<>();
            perception.put("npcCount", visibleNpcs.size());
            perception.put("monsterCount", visibleMonsters.size());
            perception.put("controlledMonsterCount", controlledMonsters.size());
            perception.put("dropCount", visibleDrops.size());
            perception.put("playerCount", visiblePlayers.size());
            perception.put("chatCount", recentChat.size());
            perception.put("npcs", sampleVisible(visibleNpcs, 12));
            perception.put("monsters", sampleVisible(visibleMonsters, 12));
            perception.put("controlledMonsters", sampleControlledMonsters(12));
            perception.put("drops", sampleVisible(visibleDrops, 12));
            perception.put("players", sampleVisible(visiblePlayers, 12));
            perception.put("recentChat", new ArrayList<>(recentChat));
            snapshot.put("perception", perception);
            snapshot.put("protocol", protocol);
            snapshot.put("recentPackets", new ArrayList<>(recentObservations));
            return snapshot;
        }

        private List<Map<String, Object>> sampleVisible(Map<Integer, Map<String, Object>> source, int limit) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> value : source.values()) {
                result.add(new LinkedHashMap<>(value));
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        }

        private List<Map<String, Object>> sampleControlledMonsters(int limit) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ControlledMonsterState state : controlledMonsters.values()) {
                result.add(state.snapshot());
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        }
    }
}
