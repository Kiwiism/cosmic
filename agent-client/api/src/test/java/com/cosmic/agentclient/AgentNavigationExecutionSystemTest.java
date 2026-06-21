package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNavigationExecutionSystemTest {
    private static final int MAP_ID = 10000;

    @Test
    void localTargetOnSameFootholdProducesSmallPhysicsFriendlyWalkStep() {
        Fixture fixture = new Fixture();
        fixture.foothold(MAP_ID, 1, 0, 300, 220, 300, 0, 0, false);

        AgentNavigationExecutionSystem.NavigationStep step = fixture.system().nextStep(
                new AgentNavigationExecutionSystem.NavigationContext(
                        1, 1, MAP_ID, 40, 300, 1, 0, 40, 1,
                        null, 160, 300, "nearby NPC"
                )
        );

        assertThat(step.blocked()).isFalse();
        assertThat(step.mode()).isEqualTo("FOOTHOLD_LOCAL_TARGET_APPROACH");
        assertThat(step.x()).isEqualTo(52);
        assertThat(step.y()).isEqualTo(300);
        assertThat(step.foothold()).isEqualTo(1);
        assertThat(step.durationMillis()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void localTargetOnHigherPlatformUsesLadderRopeGraphEdgeBeforeWalking() {
        Fixture fixture = new Fixture();
        fixture.foothold(MAP_ID, 1, 0, 300, 140, 300, 0, 0, false);
        fixture.foothold(MAP_ID, 2, 0, 180, 140, 180, 0, 0, false);
        fixture.ladder(MAP_ID, 9, true, 50, 180, 300);

        AgentNavigationExecutionSystem.NavigationStep step = fixture.system().nextStep(
                new AgentNavigationExecutionSystem.NavigationContext(
                        1, 1, MAP_ID, 50, 300, 1, 0, 50, 1,
                        null, 70, 180, "upper platform NPC"
                )
        );

        assertThat(step.blocked()).isFalse();
        assertThat(step.mode()).contains("CLIMB");
        assertThat(step.stance()).isEqualTo(16);
        assertThat(step.x()).isEqualTo(50);
        assertThat(step.detail()).extractingByKey("localGraph").isNotNull();
    }

    @Test
    void targetMapRouteApproachesPortalThenMarksPortalReadyAtArrival() {
        Fixture fixture = new Fixture();
        fixture.foothold(MAP_ID, 1, 0, 300, 220, 300, 0, 0, false);
        fixture.foothold(20000, 1, 0, 300, 220, 300, 0, 0, false);
        fixture.portal(MAP_ID, 2, "east00", 2, 20000, "west00", 200, 300);

        AgentNavigationExecutionSystem.NavigationStep approach = fixture.system().nextStep(
                new AgentNavigationExecutionSystem.NavigationContext(
                        1, 1, MAP_ID, 160, 300, 1, 0, 160, 1, 20000
                )
        );
        AgentNavigationExecutionSystem.NavigationStep ready = fixture.system().nextStep(
                new AgentNavigationExecutionSystem.NavigationContext(
                        1, 1, MAP_ID, 200, 300, 1, 0, 160, 1, 20000
                )
        );

        assertThat(approach.blocked()).isFalse();
        assertThat(approach.mode()).contains("PORTAL");
        assertThat(ready.requiresPortalEntry()).isTrue();
        assertThat(ready.portalName()).contains("east00");
    }

    @Test
    void runtimeMovementProducesContinuousFiftyMillisecondStepsForOtherClients() {
        Fixture fixture = new Fixture();
        fixture.foothold(MAP_ID, 1, 0, 300, 220, 300, 0, 0, false);
        AgentMovementRuntimeSystem runtime = new AgentMovementRuntimeSystem(fixture.repository(), new AgentPhysicsSystem());

        AgentMovementRuntimeSystem.RuntimeStep first = runtime.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                1, 1, MAP_ID, 40, 300, 1, 1,
                java.util.Optional.empty(),
                AgentPhysicsSystem.MovementIntent.walk(1)
        ));
        AgentMovementRuntimeSystem.RuntimeStep second = runtime.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                1, 1, MAP_ID,
                first.physicsStep().current().x(),
                first.physicsStep().current().y(),
                first.physicsStep().current().footholdId(),
                first.physicsStep().current().direction(),
                java.util.Optional.of(first.physicsStep().current()),
                AgentPhysicsSystem.MovementIntent.walk(1)
        ));

        assertThat(first.physicsStep().durationMillis()).isEqualTo(AgentPhysicsSystem.TICK_MILLIS);
        assertThat(second.physicsStep().durationMillis()).isEqualTo(AgentPhysicsSystem.TICK_MILLIS);
        assertThat(second.physicsStep().current().x() - first.physicsStep().current().x()).isBetween(1, 8);
        assertThat(first.body()[11]).isEqualTo((byte) 2);
        assertThat(second.detail()).extractingByKey("packet").asString().contains("WALK");
    }

    private static final class Fixture {
        private final FixtureJdbcTemplate jdbc = new FixtureJdbcTemplate();
        private final AgentMapGeometryRepository repository = new AgentMapGeometryRepository(null) {
            @Override
            AgentMapGeometry load(int mapId) {
                return new AgentMapGeometry(mapId,
                        jdbc.footholds(mapId).stream()
                                .map(row -> new AgentMapGeometry.Foothold(
                                        number(row, "foothold_id"),
                                        number(row, "x1"),
                                        number(row, "y1"),
                                        number(row, "x2"),
                                        number(row, "y2"),
                                        number(row, "prev_foothold_id"),
                                        number(row, "next_foothold_id"),
                                        number(row, "forbid_fall_down") != 0
                                ))
                                .toList(),
                        jdbc.ladders(mapId).stream()
                                .map(row -> new AgentMapGeometry.LadderRope(
                                        number(row, "object_index"),
                                        number(row, "is_ladder") != 0,
                                        number(row, "is_upper_foothold") != 0,
                                        number(row, "x"),
                                        number(row, "y1"),
                                        number(row, "y2"),
                                        number(row, "page"),
                                        string(row, "source_path")
                                ))
                                .toList(),
                        jdbc.portals(mapId).stream()
                                .map(row -> new AgentMapGeometry.Portal(
                                        number(row, "portal_index"),
                                        string(row, "portal_name"),
                                        number(row, "portal_type"),
                                        number(row, "target_map_id"),
                                        string(row, "target_portal_name"),
                                        number(row, "x"),
                                        number(row, "y"),
                                        string(row, "source_path")
                                ))
                                .toList());
            }
        };

        private AgentNavigationExecutionSystem system() {
            return new AgentNavigationExecutionSystem(jdbc, new AgentPhysicsSystem(), repository,
                    new AgentNavigationGraphBuilder());
        }

        private AgentMapGeometryRepository repository() {
            return repository;
        }

        private void foothold(int mapId, int id, int x1, int y1, int x2, int y2, int prev, int next,
                              boolean forbidFallDown) {
            jdbc.add("agent_map_footholds", mapId, Map.of(
                    "foothold_id", id,
                    "x1", x1,
                    "y1", y1,
                    "x2", x2,
                    "y2", y2,
                    "prev_foothold_id", prev,
                    "next_foothold_id", next,
                    "forbid_fall_down", forbidFallDown ? 1 : 0
            ));
        }

        private void ladder(int mapId, int index, boolean ladder, int x, int y1, int y2) {
            jdbc.add("agent_map_ladder_ropes", mapId, Map.of(
                    "object_index", index,
                    "is_ladder", ladder ? 1 : 0,
                    "is_upper_foothold", 0,
                    "x", x,
                    "y1", y1,
                    "y2", y2,
                    "page", 0,
                    "source_path", "test"
            ));
        }

        private void portal(int mapId, int index, String name, int type, int targetMapId,
                            String targetPortalName, int x, int y) {
            jdbc.add("agent_map_portals", mapId, Map.of(
                    "map_id", mapId,
                    "portal_index", index,
                    "portal_name", name,
                    "portal_type", type,
                    "target_map_id", targetMapId,
                    "target_portal_name", targetPortalName,
                    "x", x,
                    "y", y,
                    "source_path", "test"
            ));
        }
    }

    private static final class FixtureJdbcTemplate extends JdbcTemplate {
        private final Map<String, Map<Integer, List<Map<String, Object>>>> tables = new LinkedHashMap<>();

        private void add(String table, int mapId, Map<String, Object> row) {
            tables.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(mapId, ignored -> new ArrayList<>())
                    .add(new LinkedHashMap<>(row));
        }

        private List<Map<String, Object>> footholds(int mapId) {
            return rows("agent_map_footholds", mapId);
        }

        private List<Map<String, Object>> ladders(int mapId) {
            return rows("agent_map_ladder_ropes", mapId);
        }

        private List<Map<String, Object>> portals(int mapId) {
            return rows("agent_map_portals", mapId);
        }

        private List<Map<String, Object>> rows(String table, int mapId) {
            return tables.getOrDefault(table, Map.of()).getOrDefault(mapId, List.of());
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            int mapId = ((Number) args[0]).intValue();
            String table = sql.contains("agent_map_ladder_ropes") ? "agent_map_ladder_ropes"
                    : sql.contains("agent_map_portals") ? "agent_map_portals"
                    : "agent_map_footholds";
            List<Map<String, Object>> candidates = rows(table, mapId);
            if (sql.contains("foothold_id = ?") && args.length > 1) {
                int footholdId = ((Number) args[1]).intValue();
                candidates = candidates.stream()
                        .filter(row -> number(row, "foothold_id") == footholdId)
                        .toList();
            }
            List<T> result = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                try {
                    result.add(rowMapper.mapRow(resultSet(candidates.get(i)), i));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return result;
        }

        private ResultSet resultSet(Map<String, Object> row) {
            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getInt" -> number(row, String.valueOf(args[0]));
                        case "getString" -> string(row, String.valueOf(args[0]));
                        case "wasNull" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
