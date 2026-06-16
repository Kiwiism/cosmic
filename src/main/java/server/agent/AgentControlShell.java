package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reserved control boundary for future agent pilots.
 *
 * This shell only validates eligibility and records a runtime session. It does
 * not create a client, load a character into a map, move, chat, fight, trade, or
 * mutate gameplay state.
 */
public final class AgentControlShell {
    private static final Logger log = LoggerFactory.getLogger(AgentControlShell.class);

    private final AgentRuntimeService runtimeService;
    private final Map<Integer, AgentRuntimeSession> reservedSessions = new ConcurrentHashMap<>();

    public AgentControlShell(AgentRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    public AgentSpawnPlan preflight(AgentProfile profile) throws SQLException {
        return runtimeService.planSpawn(profile);
    }

    public Optional<AgentRuntimeSession> reserve(AgentProfile profile) throws SQLException {
        AgentSpawnPlan plan = preflight(profile);
        if (!plan.ready()) {
            log.info("Agent profile {} is not ready for control: {}", profile.id(), plan.controlDecision().message());
            return Optional.empty();
        }

        AgentRuntimeSession existing = reservedSessions.get(profile.id());
        if (existing != null) {
            runtimeService.heartbeat(existing, "Control shell reservation is still active");
            return Optional.of(existing);
        }

        AgentRuntimeSession session = runtimeService.startSession(profile, plan.world(), plan.channel(), plan.mapId());
        runtimeService.markIdle(session, "Reserved by dormant agent control shell");
        reservedSessions.put(profile.id(), session);
        return Optional.of(session);
    }

    public void release(AgentProfile profile, String reason) {
        AgentRuntimeSession session = reservedSessions.remove(profile.id());
        if (session != null) {
            runtimeService.stopSession(session, reason);
        }
    }

    public void forgetReservation(AgentProfile profile) {
        reservedSessions.remove(profile.id());
    }

    public int reservedSessionCount() {
        return reservedSessions.size();
    }
}
