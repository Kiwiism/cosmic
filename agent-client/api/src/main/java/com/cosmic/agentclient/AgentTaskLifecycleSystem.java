package com.cosmic.agentclient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentTaskLifecycleSystem {
    private final JdbcTemplate cmsJdbc;

    public AgentTaskLifecycleSystem(JdbcTemplate cmsJdbc) {
        this.cmsJdbc = cmsJdbc;
    }

    Map<String, Object> activateIfNeeded(int profileId, Map<String, Object> taskCard) {
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("source", taskCard.getOrDefault("source", "task"));
        lifecycle.put("runMode", runMode(taskCard));
        lifecycle.put("repeatPolicy", repeatPolicy(taskCard));

        Optional<Integer> queueId = integer(taskCard.get("queueId"));
        if (queueId.isPresent()) {
            String status = String.valueOf(taskCard.getOrDefault("queueStatus", "PENDING")).toUpperCase();
            lifecycle.put("queueId", queueId.get());
            lifecycle.put("status", status);
            if ("PENDING".equals(status)) {
                cmsJdbc.update("""
                        UPDATE agent_task_queue
                        SET status='ACTIVE',
                            starts_at=COALESCE(starts_at, CURRENT_TIMESTAMP),
                            notes=?
                        WHERE id=?
                          AND agent_profile_id=?
                          AND status='PENDING'
                        """, clippedNote("Activated by AgentTaskLifecycleSystem"), queueId.get(), profileId);
                taskCard.put("queueStatus", "ACTIVE");
                taskCard.put("startsAt", Instant.now());
                lifecycle.put("status", "ACTIVE");
                lifecycle.put("transition", "PENDING_TO_ACTIVE");
            }
        }
        return lifecycle;
    }

    Optional<TaskCompletion> evaluateCompletion(Map<String, Object> taskCard, int currentMapId,
                                                Map<String, Object> observed) {
        Map<String, Object> config = objectMap(taskCard.get("config"));
        Map<String, Object> endGoal = objectMap(config.get("endGoal"));
        String goalType = String.valueOf(endGoal.getOrDefault("type", "")).toUpperCase();
        String runMode = runMode(taskCard);
        String repeatPolicy = repeatPolicy(taskCard);

        if (goalType.isBlank() || "MANUAL_REPLACE".equals(goalType)) {
            return Optional.empty();
        }
        if ("REUSABLE".equals(runMode) && "LOOP".equals(repeatPolicy) && "DURATION_OR_REPLACE".equals(goalType)) {
            return durationCompletion(taskCard, endGoal)
                    .map(completion -> completion.withReusableLoop(true));
        }
        if (isMapCompletion(goalType)) {
            Optional<Integer> mapId = integer(endGoal.get("mapId"))
                    .or(() -> integer(config.get("endpointMapId")))
                    .or(() -> integer(config.get("targetMapId")));
            if (mapId.isPresent() && mapId.get() == currentMapId) {
                return Optional.of(new TaskCompletion(
                        true,
                        "Reached target map " + mapId.get(),
                        Map.of("completedMapId", mapId.get()),
                        false
                ));
            }
            return Optional.empty();
        }
        if ("MAPLE_ISLAND_QUESTS_COMPLETE_AND_REACH_MAP".equals(goalType)) {
            Optional<Integer> mapId = integer(endGoal.get("mapId"));
            boolean reachedEndpoint = mapId.isPresent() && mapId.get() == currentMapId;
            boolean questsComplete = truthy(observed.get("mapleIslandQuestsComplete"))
                    || truthy(observed.get("questRouteComplete"))
                    || truthy(config.get("allowEndpointOnlyCompletion"));
            if (reachedEndpoint && questsComplete) {
                return Optional.of(new TaskCompletion(
                        true,
                        "Maple Island quest route complete and endpoint reached",
                        Map.of("completedMapId", mapId.get(), "questRouteComplete", true),
                        false
                ));
            }
            return Optional.empty();
        }
        if ("REACH_LEVEL".equals(goalType)) {
            Optional<Integer> targetLevel = integer(config.get(endGoal.get("parameter")))
                    .or(() -> integer(endGoal.get("level")))
                    .or(() -> integer(config.get("targetLevel")));
            Optional<Integer> currentLevel = integer(observed.get("level"));
            if (targetLevel.isPresent() && currentLevel.isPresent() && currentLevel.get() >= targetLevel.get()) {
                return Optional.of(new TaskCompletion(
                        true,
                        "Reached level " + currentLevel.get() + " / " + targetLevel.get(),
                        Map.of("level", currentLevel.get(), "targetLevel", targetLevel.get()),
                        false
                ));
            }
        }
        return Optional.empty();
    }

    void completeTask(int profileId, Map<String, Object> taskCard, TaskCompletion completion) {
        Optional<Integer> queueId = integer(taskCard.get("queueId"));
        if (queueId.isPresent()) {
            if (completion.reusableLoop()) {
                cmsJdbc.update("""
                        UPDATE agent_task_queue
                        SET starts_at=CURRENT_TIMESTAMP,
                            notes=?
                        WHERE id=?
                          AND agent_profile_id=?
                        """, clippedNote("Loop checkpoint: " + completion.reason()), queueId.get(), profileId);
            } else {
                cmsJdbc.update("""
                        UPDATE agent_task_queue
                        SET status='COMPLETED',
                            completed_at=CURRENT_TIMESTAMP,
                            notes=?
                        WHERE id=?
                          AND agent_profile_id=?
                        """, clippedNote("Completed: " + completion.reason()), queueId.get(), profileId);
            }
            return;
        }

        Optional<Integer> cardId = integer(taskCard.get("cardId"));
        String slotKey = String.valueOf(taskCard.getOrDefault("slotKey", ""));
        if (cardId.isPresent() && "active_task".equals(slotKey) && isFinite(taskCard)) {
            cmsJdbc.update("""
                    UPDATE agent_card_loadouts
                    SET enabled=0,
                        notes=?
                    WHERE agent_profile_id=?
                      AND card_id=?
                      AND slot_key='active_task'
                      AND enabled=1
                    """, clippedNote("Completed and discarded: " + completion.reason()), profileId, cardId.get());
        }
    }

    void completeExpiredQueueTasks(int profileId) {
        cmsJdbc.update("""
                UPDATE agent_task_queue
                SET status='COMPLETED',
                    completed_at=CURRENT_TIMESTAMP,
                    notes='Completed because expires_at passed before selection'
                WHERE agent_profile_id=?
                  AND status='ACTIVE'
                  AND expires_at IS NOT NULL
                  AND expires_at < CURRENT_TIMESTAMP
                """, profileId);
    }

    private Optional<TaskCompletion> durationCompletion(Map<String, Object> taskCard, Map<String, Object> endGoal) {
        Optional<Integer> minutes = integer(endGoal.get("durationMinutes"));
        Optional<Instant> start = instant(taskCard.get("startsAt"));
        if (minutes.isEmpty() || start.isEmpty()) {
            return Optional.empty();
        }
        Duration elapsed = Duration.between(start.get(), Instant.now());
        if (elapsed.toMinutes() < minutes.get()) {
            return Optional.empty();
        }
        return Optional.of(new TaskCompletion(
                true,
                "Duration goal elapsed after " + elapsed.toMinutes() + " minute(s)",
                Map.of("elapsedMinutes", elapsed.toMinutes(), "durationMinutes", minutes.get()),
                false
        ));
    }

    private boolean isMapCompletion(String goalType) {
        return switch (goalType) {
            case "MAP", "REACH_MAP", "ARRIVE_MAP", "END_AT_MAP" -> true;
            default -> false;
        };
    }

    private boolean isFinite(Map<String, Object> taskCard) {
        String runMode = runMode(taskCard);
        String repeatPolicy = repeatPolicy(taskCard);
        return "FINITE".equals(runMode) || "ONCE".equals(repeatPolicy);
    }

    private String runMode(Map<String, Object> taskCard) {
        Map<String, Object> config = objectMap(taskCard.get("config"));
        return String.valueOf(taskCard.getOrDefault("runMode", config.getOrDefault("runMode", "FINITE"))).toUpperCase();
    }

    private String repeatPolicy(Map<String, Object> taskCard) {
        Map<String, Object> config = objectMap(taskCard.get("config"));
        return String.valueOf(taskCard.getOrDefault("repeatPolicy", config.getOrDefault("repeatPolicy", "ONCE"))).toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private Optional<Integer> integer(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Optional.of(Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<Instant> instant(Object value) {
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return Optional.of(timestamp.toInstant());
        }
        if (value instanceof LocalDateTime dateTime) {
            return Optional.of(dateTime.atZone(ZoneId.systemDefault()).toInstant());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Optional.of(Instant.parse(String.valueOf(value)));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return switch (String.valueOf(value).trim().toLowerCase()) {
            case "1", "true", "yes", "y", "enabled", "on" -> true;
            default -> false;
        };
    }

    private String clippedNote(String note) {
        return note.length() <= 500 ? note : note.substring(0, 500);
    }

    record TaskCompletion(boolean complete, String reason, Map<String, Object> progress, boolean reusableLoop) {
        TaskCompletion withReusableLoop(boolean reusableLoop) {
            return new TaskCompletion(complete, reason, progress, reusableLoop);
        }
    }
}
