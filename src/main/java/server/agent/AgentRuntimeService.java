package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

public final class AgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final AgentRuntimeRepository repository;
    private final AgentControlGuard controlGuard;
    private final AgentSpawnPlanner spawnPlanner;

    public AgentRuntimeService() {
        this(new AgentRuntimeRepository(), new AgentRepository(), new AgentControlGuard());
    }

    AgentRuntimeService(AgentRuntimeRepository repository, AgentRepository agentRepository, AgentControlGuard controlGuard) {
        this.repository = repository;
        this.controlGuard = controlGuard;
        this.spawnPlanner = new AgentSpawnPlanner(agentRepository, controlGuard);
    }

    public AgentControlDecision canControl(AgentProfile profile) {
        return controlGuard.canRuntimeControl(profile);
    }

    public AgentSpawnPlan planSpawn(AgentProfile profile) throws SQLException {
        return spawnPlanner.plan(profile);
    }

    public AgentRuntimeSession startSession(AgentProfile profile, int world, int channel, int mapId) throws SQLException {
        AgentRuntimeSession session = repository.startSession(profile, world, channel, mapId);
        repository.logAction(AgentActionLogEntry.lifecycle(profile.id(), session.id(), "Agent runtime session created"));
        return session;
    }

    public void markRunning(AgentRuntimeSession session, String task) throws SQLException {
        repository.updateSessionState(session.id(), AgentRuntimeState.RUNNING, task);
    }

    public void markIdle(AgentRuntimeSession session, String task) throws SQLException {
        repository.updateSessionState(session.id(), AgentRuntimeState.IDLE, task);
    }

    public void heartbeat(AgentRuntimeSession session, String task) throws SQLException {
        repository.heartbeat(session.id(), task);
    }

    public void stopSession(AgentRuntimeSession session, String reason) {
        try {
            repository.endSession(session.id(), AgentRuntimeState.STOPPED, reason);
            repository.logAction(AgentActionLogEntry.lifecycle(session.agentProfileId(), session.id(), reason));
        } catch (SQLException e) {
            log.warn("Failed to stop agent runtime session {}", session.id(), e);
        }
    }

    public void logLifecycle(int profileId, long sessionId, Integer world, Integer channel, Integer mapId, String message) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                profileId,
                sessionId,
                "LIFECYCLE",
                AgentActionStatus.OK,
                world,
                channel,
                mapId,
                null,
                null,
                message,
                null
        ));
    }

    public void logPlannedIntent(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource,
            String message
    ) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                managed.profileId(),
                managed.session().id(),
                "INTENT_PLAN",
                intent.type() == AgentIntentType.UNKNOWN ? AgentActionStatus.BLOCKED : AgentActionStatus.OK,
                perception.world(),
                perception.channel(),
                perception.mapId(),
                intentTargetType(intent),
                null,
                message,
                plannedIntentDetailsJson(intent, perception, scriptSource)
        ));
    }

    public void logDispatchedIntent(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource,
            AgentIntentDispatchResult result
    ) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                managed.profileId(),
                managed.session().id(),
                "INTENT_DISPATCH",
                result.status(),
                perception.world(),
                perception.channel(),
                perception.mapId(),
                intentTargetType(intent),
                null,
                result.message(),
                dispatchedIntentDetailsJson(intent, perception, scriptSource, result)
        ));
    }

    public void failSession(AgentRuntimeSession session, String reason) {
        try {
            repository.endSession(session.id(), AgentRuntimeState.FAILED, reason);
            repository.logAction(new AgentActionLogEntry(
                    session.agentProfileId(),
                    session.id(),
                    "LIFECYCLE",
                    AgentActionStatus.FAILED,
                    session.world(),
                    session.channel(),
                    session.mapId(),
                    null,
                    null,
                    reason,
                    null
            ));
        } catch (SQLException e) {
            log.warn("Failed to mark agent runtime session {} as failed", session.id(), e);
        }
    }

    private String intentTargetType(AgentIntent intent) {
        return switch (intent.type()) {
            case SAY -> "CHAT";
            case ROAM, MOVE, MOVE_TO_MAP, USE_PORTAL -> "NAVIGATION";
            case ATTACK, GRIND -> "COMBAT";
            case LOOT, SHOP, TRADE -> "ECONOMY";
            case NPC -> "NPC";
            case PARTY -> "SOCIAL";
            case USE_ITEM, EQUIP -> "INVENTORY";
            case IDLE, WAIT -> "SELF";
            case UNKNOWN -> "SCRIPT";
        };
    }

    private String plannedIntentDetailsJson(
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource
    ) {
        return "{"
                + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                + "\"durationMillis\":" + intent.durationMillis() + ","
                + "\"scriptSource\":\"" + escapeJson(scriptSource) + "\","
                + "\"perception\":" + perceptionDetailsJson(perception)
                + "}";
    }

    private String dispatchedIntentDetailsJson(
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource,
            AgentIntentDispatchResult dispatchResult
    ) {
        return "{"
                + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                + "\"durationMillis\":" + intent.durationMillis() + ","
                + "\"scriptSource\":\"" + escapeJson(scriptSource) + "\","
                + "\"dispatchMessage\":\"" + escapeJson(dispatchResult.message()) + "\","
                + "\"capability\":\"" + escapeJson(dispatchResult.capability().name()) + "\","
                + "\"policyAllowed\":" + dispatchResult.policyAllowed() + ","
                + "\"perception\":" + perceptionDetailsJson(perception)
                + "}";
    }

    private String perceptionDetailsJson(AgentPerceptionSnapshot perception) {
        return "{"
                + "\"available\":" + perception.available() + ","
                + "\"world\":" + perception.world() + ","
                + "\"channel\":" + perception.channel() + ","
                + "\"mapId\":" + perception.mapId() + ","
                + "\"position\":{\"x\":" + perception.x() + ",\"y\":" + perception.y() + "},"
                + "\"counts\":{"
                + "\"players\":" + perception.players() + ","
                + "\"monsters\":" + perception.monsters() + ","
                + "\"drops\":" + perception.drops() + ","
                + "\"npcs\":" + perception.npcs() + ","
                + "\"reactors\":" + perception.reactors()
                + "},"
                + "\"nearby\":{"
                + "\"players\":" + visibleObjectsJson(perception.nearbyPlayers()) + ","
                + "\"monsters\":" + visibleObjectsJson(perception.nearbyMonsters()) + ","
                + "\"drops\":" + visibleObjectsJson(perception.nearbyDrops()) + ","
                + "\"npcs\":" + visibleObjectsJson(perception.nearbyNpcs()) + ","
                + "\"reactors\":" + visibleObjectsJson(perception.nearbyReactors())
                + "},"
                + "\"message\":\"" + escapeJson(perception.message()) + "\""
                + "}";
    }

    private String visibleObjectsJson(List<AgentPerceptionSnapshot.AgentVisibleObject> objects) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < objects.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(visibleObjectJson(objects.get(i)));
        }
        return builder.append(']').toString();
    }

    private String visibleObjectJson(AgentPerceptionSnapshot.AgentVisibleObject object) {
        return "{"
                + "\"type\":\"" + escapeJson(object.type()) + "\","
                + "\"objectId\":" + object.objectId() + ","
                + "\"templateId\":" + nullableNumber(object.templateId()) + ","
                + "\"name\":\"" + escapeJson(object.name()) + "\","
                + "\"x\":" + object.x() + ","
                + "\"y\":" + object.y() + ","
                + "\"distanceSq\":" + object.distanceSq() + ","
                + "\"hp\":" + nullableNumber(object.hp()) + ","
                + "\"maxHp\":" + nullableNumber(object.maxHp()) + ","
                + "\"level\":" + nullableNumber(object.level()) + ","
                + "\"quantity\":" + nullableNumber(object.quantity()) + ","
                + "\"meso\":" + nullableNumber(object.meso()) + ","
                + "\"alive\":" + nullableBoolean(object.alive()) + ","
                + "\"state\":" + nullableNumber(object.state())
                + "}";
    }

    private String nullableNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String nullableBoolean(Boolean value) {
        return value == null ? "null" : value.toString();
    }

    private String escapeJson(String value) {
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
}
