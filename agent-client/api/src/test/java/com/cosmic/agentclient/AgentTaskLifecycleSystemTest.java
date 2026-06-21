package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskLifecycleSystemTest {
    private final AgentTaskLifecycleSystem lifecycle = new AgentTaskLifecycleSystem(null);

    @Test
    void completesFiniteTaskWhenTargetMapIsReached() {
        Map<String, Object> task = task("FINITE", "ONCE", Map.of(
                "endGoal", Map.of("type", "REACH_MAP", "mapId", 60000)
        ));

        var completion = lifecycle.evaluateCompletion(task, 60000, Map.of());

        assertThat(completion).isPresent();
        assertThat(completion.get().reason()).contains("60000");
        assertThat(completion.get().reusableLoop()).isFalse();
    }

    @Test
    void doesNotCompleteMapleIslandTaskWithoutQuestCompletionSignal() {
        Map<String, Object> task = task("FINITE", "ONCE", Map.of(
                "endGoal", Map.of("type", "MAPLE_ISLAND_QUESTS_COMPLETE_AND_REACH_MAP", "mapId", 60000)
        ));

        var completion = lifecycle.evaluateCompletion(task, 60000, Map.of());

        assertThat(completion).isEmpty();
    }

    @Test
    void completesMapleIslandTaskWhenQuestSignalAndEndpointAreBothPresent() {
        Map<String, Object> task = task("FINITE", "ONCE", Map.of(
                "endGoal", Map.of("type", "MAPLE_ISLAND_QUESTS_COMPLETE_AND_REACH_MAP", "mapId", 60000)
        ));

        var completion = lifecycle.evaluateCompletion(task, 60000, Map.of("mapleIslandQuestsComplete", true));

        assertThat(completion).isPresent();
        assertThat(completion.get().progress()).containsEntry("questRouteComplete", true);
    }

    @Test
    void reusableDurationTasksLoopInsteadOfTerminatingQueue() {
        Map<String, Object> task = task("REUSABLE", "LOOP", Map.of(
                "endGoal", Map.of("type", "DURATION_OR_REPLACE", "durationMinutes", 1)
        ));
        task.put("startsAt", Instant.now().minusSeconds(180));

        var completion = lifecycle.evaluateCompletion(task, 100000000, Map.of());

        assertThat(completion).isPresent();
        assertThat(completion.get().reusableLoop()).isTrue();
    }

    private Map<String, Object> task(String runMode, String repeatPolicy, Map<String, Object> config) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("runMode", runMode);
        task.put("repeatPolicy", repeatPolicy);
        task.put("config", config);
        return task;
    }
}
