package server.agent;

public record AgentActionLogEntry(
        int agentProfileId,
        Long runtimeSessionId,
        String actionType,
        AgentActionStatus status,
        Integer world,
        Integer channel,
        Integer mapId,
        String targetType,
        Long targetId,
        String message,
        String detailsJson
) {
    public AgentActionLogEntry {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("Agent action type is required");
        }
        if (status == null) {
            status = AgentActionStatus.OK;
        }
    }

    public static AgentActionLogEntry lifecycle(int agentProfileId, Long runtimeSessionId, String message) {
        return new AgentActionLogEntry(
                agentProfileId,
                runtimeSessionId,
                "LIFECYCLE",
                AgentActionStatus.OK,
                null,
                null,
                null,
                null,
                null,
                message,
                null
        );
    }
}
