package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSustainSystemTest {
    private final AgentSustainSystem system = new AgentSustainSystem();

    @Test
    void plansPotionUseWhenHpIsLowAndSlotIsConfigured() {
        AgentSustainSystem.SustainPlan plan = system.plan(new AgentSustainSystem.SustainContext(
                true, Optional.of(20), 30, 2000000, 1, false, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentSustainSystem.SustainAction.USE_HP_ITEM);
        assertThat(plan.executable()).isTrue();
        assertThat(plan.itemId()).isEqualTo(2000000);
        assertThat(plan.slot()).isEqualTo(1);
    }

    @Test
    void doesNotPretendVirtualDebtCanHealWithoutServerBridge() {
        AgentSustainSystem.SustainPlan plan = system.plan(new AgentSustainSystem.SustainContext(
                true, Optional.of(20), 30, 0, 0, true, 0, 10_000
        ));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.blockedReason()).contains("normal client still needs an item slot");
    }
}
