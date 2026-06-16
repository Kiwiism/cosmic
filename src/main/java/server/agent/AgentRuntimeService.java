package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public final class AgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final AgentRuntimeRepository repository;

    public AgentRuntimeService() {
        this(new AgentRuntimeRepository());
    }

    AgentRuntimeService(AgentRuntimeRepository repository) {
        this.repository = repository;
    }

    public AgentRuntimeSession startSession(AgentProfile profile, int world, int channel, int mapId) throws SQLException {
        AgentRuntimeSession session = repository.startSession(profile, world, channel, mapId);
        repository.logAction(AgentActionLogEntry.lifecycle(profile.id(), session.id(), "Agent runtime session created"));
        return session;
    }

    public void markRunning(AgentRuntimeSession session, String task) throws SQLException {
        repository.updateSessionState(session.id(), AgentRuntimeState.RUNNING, task);
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
