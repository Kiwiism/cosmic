package server.agent;

import client.Character;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only environment snapshot service for future agents.
 *
 * The snapshot exposes compact nearby-object summaries only. It deliberately
 * avoids pathing, target selection, and gameplay mutations.
 */
public final class AgentPerceptionService {
    private static final int NEARBY_LIMIT = 12;

    public AgentPerceptionSnapshot snapshot(AgentManagedCharacter managed) {
        MapleMap map = managed.character().getMap();
        if (map == null) {
            return AgentPerceptionSnapshot.unavailable(managed.spawnPlan(), "Agent character is not attached to a map");
        }

        return snapshotMap(
                managed.spawnPlan().world(),
                managed.spawnPlan().channel(),
                map,
                managed.character().getPosition(),
                "Entered agent map snapshot captured"
        );
    }

    public AgentPerceptionSnapshot snapshot(AgentSpawnPlan plan) {
        if (!plan.ready()) {
            return AgentPerceptionSnapshot.unavailable(plan, plan.controlDecision().message());
        }

        try {
            World world = Server.getInstance().getWorld(plan.world());
            if (world == null) {
                return AgentPerceptionSnapshot.unavailable(plan, "World is not loaded");
            }

            Channel channel = world.getChannel(plan.channel());
            if (channel == null) {
                return AgentPerceptionSnapshot.unavailable(plan, "Channel is not loaded");
            }

            MapleMap map = channel.getMapFactory().getMap(plan.mapId());
            if (map == null) {
                return AgentPerceptionSnapshot.unavailable(plan, "Map is not loaded");
            }

            return snapshotMap(plan.world(), plan.channel(), map, new Point(0, 0), "Map snapshot captured");
        } catch (RuntimeException e) {
            return AgentPerceptionSnapshot.unavailable(plan, "Unable to capture map snapshot: " + e.getMessage());
        }
    }

    private AgentPerceptionSnapshot snapshotMap(int world, int channel, MapleMap map, Point origin, String message) {
        List<Character> players = List.copyOf(map.getMapPlayers().values());
        List<AgentPerceptionSnapshot.AgentVisibleObject> nearbyPlayers = players.stream()
                .map(player -> playerObject(player, origin))
                .sorted(byDistance())
                .limit(NEARBY_LIMIT)
                .toList();
        List<MapObject> monsters = map.getMapObjectsInRange(origin, Double.POSITIVE_INFINITY, List.of(MapObjectType.MONSTER));
        List<MapObject> drops = map.getMapObjectsInRange(origin, Double.POSITIVE_INFINITY, List.of(MapObjectType.ITEM));
        List<MapObject> npcs = map.getMapObjectsInRange(origin, Double.POSITIVE_INFINITY, List.of(MapObjectType.NPC));
        List<MapObject> reactors = map.getMapObjectsInRange(origin, Double.POSITIVE_INFINITY, List.of(MapObjectType.REACTOR));

        return new AgentPerceptionSnapshot(
                true,
                world,
                channel,
                map.getId(),
                origin.x,
                origin.y,
                players.size(),
                monsters.size(),
                drops.size(),
                npcs.size(),
                reactors.size(),
                nearbyPlayers,
                visibleObjects(monsters, origin),
                visibleObjects(drops, origin),
                visibleObjects(npcs, origin),
                visibleObjects(reactors, origin),
                message,
                Instant.now()
        );
    }

    private List<AgentPerceptionSnapshot.AgentVisibleObject> visibleObjects(List<MapObject> objects, Point origin) {
        return objects.stream()
                .map(object -> visibleObject(object, origin))
                .filter(Objects::nonNull)
                .sorted(byDistance())
                .limit(NEARBY_LIMIT)
                .toList();
    }

    private AgentPerceptionSnapshot.AgentVisibleObject visibleObject(MapObject object, Point origin) {
        return switch (object.getType()) {
            case MONSTER -> monsterObject((Monster) object, origin);
            case ITEM -> dropObject((MapItem) object, origin);
            case NPC -> npcObject((NPC) object, origin);
            case REACTOR -> reactorObject((Reactor) object, origin);
            default -> null;
        };
    }

    private AgentPerceptionSnapshot.AgentVisibleObject playerObject(Character player, Point origin) {
        Point position = player.getPosition();
        return new AgentPerceptionSnapshot.AgentVisibleObject(
                "PLAYER",
                player.getObjectId(),
                player.getId(),
                player.getName(),
                position.x,
                position.y,
                distanceSq(origin, position),
                null,
                null,
                player.getLevel(),
                null,
                null,
                null,
                null
        );
    }

    private AgentPerceptionSnapshot.AgentVisibleObject monsterObject(Monster monster, Point origin) {
        Point position = monster.getPosition();
        return new AgentPerceptionSnapshot.AgentVisibleObject(
                "MONSTER",
                monster.getObjectId(),
                monster.getId(),
                monster.getName(),
                position.x,
                position.y,
                distanceSq(origin, position),
                monster.getHp(),
                monster.getMaxHp(),
                monster.getLevel(),
                null,
                null,
                monster.isAlive(),
                null
        );
    }

    private AgentPerceptionSnapshot.AgentVisibleObject dropObject(MapItem drop, Point origin) {
        Point position = drop.getPosition();
        Integer quantity = drop.getItem() == null ? null : (int) drop.getItem().getQuantity();
        Integer meso = drop.getMeso() > 0 ? drop.getMeso() : null;
        return new AgentPerceptionSnapshot.AgentVisibleObject(
                "DROP",
                drop.getObjectId(),
                drop.getMeso() > 0 ? null : drop.getItemId(),
                meso == null ? null : "meso",
                position.x,
                position.y,
                distanceSq(origin, position),
                null,
                null,
                null,
                quantity,
                meso,
                !drop.isPickedUp(),
                null
        );
    }

    private AgentPerceptionSnapshot.AgentVisibleObject npcObject(NPC npc, Point origin) {
        Point position = npc.getPosition();
        return new AgentPerceptionSnapshot.AgentVisibleObject(
                "NPC",
                npc.getObjectId(),
                npc.getId(),
                npc.getName(),
                position.x,
                position.y,
                distanceSq(origin, position),
                null,
                null,
                null,
                null,
                null,
                true,
                null
        );
    }

    private AgentPerceptionSnapshot.AgentVisibleObject reactorObject(Reactor reactor, Point origin) {
        Point position = reactor.getPosition();
        return new AgentPerceptionSnapshot.AgentVisibleObject(
                "REACTOR",
                reactor.getObjectId(),
                reactor.getId(),
                null,
                position.x,
                position.y,
                distanceSq(origin, position),
                null,
                null,
                null,
                null,
                null,
                reactor.isAlive(),
                (int) reactor.getState()
        );
    }

    private Comparator<AgentPerceptionSnapshot.AgentVisibleObject> byDistance() {
        return Comparator.comparingLong(AgentPerceptionSnapshot.AgentVisibleObject::distanceSq);
    }

    private long distanceSq(Point origin, Point position) {
        long dx = (long) origin.x - position.x;
        long dy = (long) origin.y - position.y;
        return dx * dx + dy * dy;
    }
}
