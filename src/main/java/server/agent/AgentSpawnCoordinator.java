package server.agent;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prepares regular characters for future agent control.
 *
 * This coordinator keeps the agent lifecycle explicit. Preparing an agent only
 * reserves and loads the character. Entering the world must be requested
 * separately, and it uses the same channel/world/map storage path as a player
 * login without social, guild, or party side effects.
 *
 * - validates that the account/character is not already controlled by a player
 * - opens an agent runtime session
 * - loads the character from the normal database path for inspection/planning
 * - can place and remove the character from channel/world/map storage
 */
public final class AgentSpawnCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentSpawnCoordinator.class);

    private final AgentRuntimeService runtimeService;
    private final AgentControlShell controlShell;
    private final Map<Integer, AgentManagedCharacter> preparedCharacters = new ConcurrentHashMap<>();
    private final Map<Integer, AgentManagedCharacter> enteredCharacters = new ConcurrentHashMap<>();

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

        Client client = Client.createHeadlessChannelClient(
                -profile.id(),
                "agent-runtime:" + profile.id(),
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
                Instant.now(),
                null
        );
        preparedCharacters.put(profile.id(), managed);
        runtimeService.logLifecycle(profile.id(), session.get().id(), plan.world(), plan.channel(), plan.mapId(),
                "Prepared character for future agent spawn");
        log.info("Prepared agent profile {} character {} at world {} channel {} map {}",
                profile.id(), profile.characterId(), plan.world(), plan.channel(), plan.mapId());
        return Optional.of(managed);
    }

    public Optional<AgentManagedCharacter> enterWorld(AgentProfile profile) throws SQLException {
        AgentManagedCharacter entered = enteredCharacters.get(profile.id());
        if (entered != null) {
            runtimeService.heartbeat(entered.session(), "Agent character is already entered in the world");
            return Optional.of(entered);
        }

        Optional<AgentManagedCharacter> preparedOptional = prepare(profile);
        if (preparedOptional.isEmpty()) {
            return Optional.empty();
        }

        AgentManagedCharacter prepared = preparedOptional.get();
        Client client = prepared.client();
        World world = client.getWorldServer();
        Channel channel = client.getChannelServer();
        if (world == null || channel == null) {
            release(profile, "Agent could not resolve world/channel for entry");
            return Optional.empty();
        }

        if (world.getPlayerStorage().getCharacterById(profile.characterId()) != null
                || channel.getPlayerStorage().getCharacterById(profile.characterId()) != null) {
            release(profile, "Character is already present in online player storage");
            return Optional.empty();
        }

        Character character = Character.loadCharFromDB(profile.characterId(), client, true);
        client.setPlayer(character);
        client.setAccID(character.getAccountID());
        client.setGMLevel(character.gmLevel());

        MapleMap map = character.getMap();
        if (map == null) {
            release(profile, "Agent character loaded without a map");
            return Optional.empty();
        }

        channel.addPlayer(character);
        world.addPlayer(character);
        character.setEnteredChannelWorld();
        map.addPlayer(character);
        character.visitMap(map);
        character.setLoginTime(System.currentTimeMillis());

        AgentManagedCharacter managed = prepared.withCharacter(character, Instant.now()).markEntered(Instant.now());
        preparedCharacters.put(profile.id(), managed);
        enteredCharacters.put(profile.id(), managed);
        runtimeService.markRunning(managed.session(), "Agent entered world storage");
        runtimeService.logLifecycle(profile.id(), managed.session().id(), managed.spawnPlan().world(),
                managed.spawnPlan().channel(), map.getId(), "Agent entered channel/world/map storage");
        log.info("Entered agent profile {} character {} into world {} channel {} map {}",
                profile.id(), profile.characterId(), managed.spawnPlan().world(), managed.spawnPlan().channel(), map.getId());
        return Optional.of(managed);
    }

    public void release(AgentProfile profile, String reason) {
        AgentManagedCharacter managed = preparedCharacters.remove(profile.id());
        if (managed == null) {
            controlShell.release(profile, reason);
            return;
        }

        AgentManagedCharacter entered = enteredCharacters.remove(profile.id());
        boolean cleanRelease = true;
        if (entered != null) {
            cleanRelease = leaveEnteredWorld(entered, reason);
        }

        managed.client().setPlayer(null);
        if (cleanRelease) {
            runtimeService.stopSession(managed.session(), reason);
        }
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

    public int enteredCount() {
        return enteredCharacters.size();
    }

    private boolean leaveEnteredWorld(AgentManagedCharacter managed, String reason) {
        Character character = managed.character();
        try {
            character.setDisconnectedFromChannelWorld();

            MapleMap currentMap = character.getMap();
            if (currentMap != null) {
                currentMap.removePlayer(character);
            }

            character.saveCharToDB(true);

            World world = managed.client().getWorldServer();
            if (world != null) {
                world.removePlayer(character);
            } else {
                Channel channel = managed.client().getChannelServer();
                if (channel != null) {
                    channel.removePlayer(character);
                }
            }

            character.logOff();
            runtimeService.logLifecycle(managed.profileId(), managed.session().id(), managed.spawnPlan().world(),
                    managed.spawnPlan().channel(), character.getMapId(), "Agent left world storage: " + reason);
            log.info("Released entered agent profile {} character {} from world storage: {}",
                    managed.profileId(), managed.characterId(), reason);
            return true;
        } catch (Exception e) {
            runtimeService.failSession(managed.session(), "Failed to release entered agent: " + e.getMessage());
            log.warn("Failed to release entered agent profile {} character {}", managed.profileId(),
                    managed.characterId(), e);
            return false;
        }
    }
}
