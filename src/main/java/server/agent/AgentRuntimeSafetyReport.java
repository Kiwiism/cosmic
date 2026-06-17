package server.agent;

import java.util.List;

public record AgentRuntimeSafetyReport(
        boolean warning,
        String severity,
        List<String> issues,
        long elapsedMillis,
        int repeatedDispatchCount,
        AgentActionStatus dispatchStatus,
        AgentIntentType intentType,
        AgentIntentCapability capability
) {
    public AgentRuntimeSafetyReport {
        issues = List.copyOf(issues == null ? List.of() : issues);
    }
}
