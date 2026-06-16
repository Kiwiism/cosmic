package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

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
}
