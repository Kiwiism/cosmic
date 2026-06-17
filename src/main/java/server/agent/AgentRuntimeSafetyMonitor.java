package server.agent;

import client.Character;
import client.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentRuntimeSafetyMonitor {
    private static final long SLOW_TICK_WARNING_MILLIS = 1_000L;
    private static final int REPEATED_BLOCK_WARNING_COUNT = 3;

    private final Map<Integer, DispatchStreak> dispatchStreaks = new ConcurrentHashMap<>();

    public AgentRuntimeSafetyReport evaluate(
            AgentManagedCharacter managed,
            AgentPerceptionSnapshot startPerception,
            AgentPerceptionSnapshot resultPerception,
            AgentIntentDispatchResult dispatchResult,
            long elapsedMillis
    ) {
        List<String> issues = new ArrayList<>();
        String severity = "OK";

        if (elapsedMillis >= SLOW_TICK_WARNING_MILLIS) {
            issues.add("Tick took " + elapsedMillis + " ms");
            severity = "WARN";
        }
        if (startPerception == null || !startPerception.available()) {
            issues.add("Initial perception unavailable: " + (startPerception == null ? "missing" : startPerception.message()));
            severity = "WARN";
        }
        if (resultPerception == null || !resultPerception.available()) {
            issues.add("Result perception unavailable: " + (resultPerception == null ? "missing" : resultPerception.message()));
            severity = "WARN";
        }

        List<String> boundaryIssues = controlBoundaryIssues(managed);
        issues.addAll(boundaryIssues);

        DispatchStreak streak = updateDispatchStreak(managed.profileId(), dispatchResult);
        if (streak.count() >= REPEATED_BLOCK_WARNING_COUNT) {
            issues.add("Repeated " + dispatchResult.status() + " dispatch for " + dispatchResult.intent().type()
                    + " x" + streak.count() + ": " + dispatchResult.message());
            severity = "WARN";
        }

        if (!boundaryIssues.isEmpty()) {
            severity = "WARN";
        }

        return new AgentRuntimeSafetyReport(
                "WARN".equals(severity),
                severity,
                issues,
                elapsedMillis,
                streak.count(),
                dispatchResult.status(),
                dispatchResult.intent().type(),
                dispatchResult.capability()
        );
    }

    private List<String> controlBoundaryIssues(AgentManagedCharacter managed) {
        List<String> issues = new ArrayList<>();
        Client client = managed.client();
        Character character = managed.character();
        if (client == null) {
            issues.add("Managed agent has no client");
            return issues;
        }
        if (character == null) {
            issues.add("Managed agent has no character");
            return issues;
        }
        if (client.getPlayer() != character) {
            issues.add("Client player reference no longer matches managed character");
        }
        if (character.getMap() == null) {
            issues.add("Managed character has no attached map");
        }
        if (!managed.enteredWorld()) {
            issues.add("Managed character is not marked as entered");
        }
        return issues;
    }

    private DispatchStreak updateDispatchStreak(int profileId, AgentIntentDispatchResult dispatchResult) {
        String signature = dispatchResult.status() + ":" + dispatchResult.intent().type() + ":" + dispatchResult.message();
        return dispatchStreaks.compute(profileId, (ignored, previous) -> {
            if (dispatchResult.status() == AgentActionStatus.OK) {
                return new DispatchStreak(signature, 1);
            }
            if (previous != null && previous.signature().equals(signature)) {
                return new DispatchStreak(signature, previous.count() + 1);
            }
            return new DispatchStreak(signature, 1);
        });
    }

    private record DispatchStreak(String signature, int count) {
    }
}
