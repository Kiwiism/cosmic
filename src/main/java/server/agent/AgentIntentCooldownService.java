package server.agent;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

public final class AgentIntentCooldownService {
    private static final long DEFAULT_SELF_COOLDOWN_MILLIS = 0L;
    private static final long DEFAULT_CHAT_COOLDOWN_MILLIS = 10_000L;
    private static final long DEFAULT_NAVIGATION_COOLDOWN_MILLIS = 1_000L;
    private static final long DEFAULT_COMBAT_COOLDOWN_MILLIS = 1_000L;
    private static final long DEFAULT_LOOT_COOLDOWN_MILLIS = 750L;
    private static final long DEFAULT_NPC_COOLDOWN_MILLIS = 2_000L;
    private static final long DEFAULT_SHOP_COOLDOWN_MILLIS = 2_000L;
    private static final long DEFAULT_TRADE_COOLDOWN_MILLIS = 5_000L;
    private static final long DEFAULT_PARTY_COOLDOWN_MILLIS = 3_000L;
    private static final long DEFAULT_INVENTORY_COOLDOWN_MILLIS = 1_500L;
    private static final long DEFAULT_SKILL_COOLDOWN_MILLIS = 1_000L;
    private static final long DEFAULT_SCRIPT_COOLDOWN_MILLIS = 1_000L;

    private final AgentPolicyRepository policyRepository;
    private final AgentRuntimeRepository runtimeRepository;

    public AgentIntentCooldownService(AgentPolicyRepository policyRepository, AgentRuntimeRepository runtimeRepository) {
        this.policyRepository = policyRepository;
        this.runtimeRepository = runtimeRepository;
    }

    public AgentIntentCooldownDecision evaluate(AgentProfile profile, AgentRuntimeSession session, AgentIntent intent) throws SQLException {
        AgentIntentCapability capability = AgentIntentCapability.fromIntent(intent.type());
        long cooldownMillis = policyRepository.longPolicy(
                profile.id(),
                "cooldown." + intent.type().name().toLowerCase() + ".millis",
                policyRepository.longPolicy(
                        profile.id(),
                        "cooldown." + capability.name().toLowerCase() + ".millis",
                        defaultCooldownMillis(capability)
                )
        );
        if (cooldownMillis <= 0L) {
            return AgentIntentCooldownDecision.allowed(0L);
        }

        Instant lastDispatch = runtimeRepository.latestSessionIntentDispatch(session.id(), intent.type());
        if (lastDispatch == null) {
            return AgentIntentCooldownDecision.allowed(cooldownMillis);
        }

        long elapsedMillis = Duration.between(lastDispatch, Instant.now()).toMillis();
        if (elapsedMillis >= cooldownMillis) {
            return AgentIntentCooldownDecision.allowed(cooldownMillis);
        }
        return AgentIntentCooldownDecision.blocked(cooldownMillis, cooldownMillis - elapsedMillis, intent.type());
    }

    private long defaultCooldownMillis(AgentIntentCapability capability) {
        return switch (capability) {
            case SELF -> DEFAULT_SELF_COOLDOWN_MILLIS;
            case CHAT -> DEFAULT_CHAT_COOLDOWN_MILLIS;
            case NAVIGATION -> DEFAULT_NAVIGATION_COOLDOWN_MILLIS;
            case COMBAT -> DEFAULT_COMBAT_COOLDOWN_MILLIS;
            case LOOT -> DEFAULT_LOOT_COOLDOWN_MILLIS;
            case NPC -> DEFAULT_NPC_COOLDOWN_MILLIS;
            case SHOP -> DEFAULT_SHOP_COOLDOWN_MILLIS;
            case TRADE -> DEFAULT_TRADE_COOLDOWN_MILLIS;
            case PARTY -> DEFAULT_PARTY_COOLDOWN_MILLIS;
            case INVENTORY -> DEFAULT_INVENTORY_COOLDOWN_MILLIS;
            case SKILL -> DEFAULT_SKILL_COOLDOWN_MILLIS;
            case SCRIPT -> DEFAULT_SCRIPT_COOLDOWN_MILLIS;
        };
    }
}
