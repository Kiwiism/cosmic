package server.agent;

import client.Character;
import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prepares regular characters for future agent control.
 *
 * This is intentionally not a visible map spawn yet. A visible spawn requires a
 * headless packet sink or a refactor of the player-enter-map path so server-side
 * actors do not need a Netty client. For now this coordinator only:
 *
 * - validates that the account/character is not already controlled by a player
 * - opens an agent runtime session
 * - loads the character from the normal database path for inspection/planning
 * - releases the prepared handle without touching map/world player storage
 */
public final class AgentSpawnCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentSpawnCoordinator.class);

    private final AgentRuntimeService runtimeService;
    private final AgentControlShell controlShell;
    private final Map<Integer, AgentManagedCharacter> preparedCharacters = new ConcurrentHashMap<>();

    public AgentSpawnCoordinator(AgentRuntimeService runtimeService, AgentControlShell controlShell) {
        this.runtimeService = runtimeService;
        this.controlShell = controlShell;
    }

    public Optional<AgentManagedCharacter> prepare(AgentProfile profile) throws SQLException {
        AgentManagedCharacter existing = preparedCharacters.get(profile.id());
        if (existing != null) {
            runtimeService.heartbeat(existing.session(), "Prepared agent character is still reserved");
            return Optional.of(existing);
        }

        Optional<AgentRuntimeSession> session = controlShell.reserve(profile);
        if (session.isEmpty()) {
            return Optional.empty();
        }

        AgentSpawnPlan plan = controlShell.preflight(profile);
        if (!plan.ready()) {
            controlShell.release(profile, plan.controlDecision().message());
            return Optional.empty();
        }

        Client client = Client.createChannelClient(
                -profile.id(),
                "agent-runtime:" + profile.id(),
                null,
                plan.world(),
                plan.channel()
        );
        Character character = Character.loadCharFromDB(profile.characterId(), client, false);
        client.setPlayer(character);
        client.setAccID(character.getAccountID());
        client.setGMLevel(character.gmLevel());

        AgentManagedCharacter managed = new AgentManagedCharacter(
                profile,
                session.get(),
                client,
                character,
                plan,
                Instant.now()
        );
        preparedCharacters.put(profile.id(), managed);
        runtimeService.logLifecycle(profile.id(), session.get().id(), plan.world(), plan.channel(), plan.mapId(),
                "Prepared character for future agent spawn");
        log.info("Prepared agent profile {} character {} at world {} channel {} map {}",
                profile.id(), profile.characterId(), plan.world(), plan.channel(), plan.mapId());
        return Optional.of(managed);
    }

    public void release(AgentProfile profile, String reason) {
        AgentManagedCharacter managed = preparedCharacters.remove(profile.id());
        if (managed == null) {
            controlShell.release(profile, reason);
            return;
        }
        managed.client().setPlayer(null);
        runtimeService.stopSession(managed.session(), reason);
        controlShell.forgetReservation(profile);
    }

    public void releaseAll(String reason) {
        for (AgentManagedCharacter managed : preparedCharacters.values()) {
            release(managed.profile(), reason);
        }
    }

    public int preparedCount() {
        return preparedCharacters.size();
    }
}
