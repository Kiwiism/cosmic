package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCombatSystemTest {
    private final AgentCombatSystem system = new AgentCombatSystem();

    @Test
    void blocksAttackWhenMonsterIsTooFarAway() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 20);
        target.put("x", 500);
        target.put("y", 200);

        AgentCombatSystem.CombatPlan plan = system.plan(new AgentCombatSystem.CombatContext(
                target, 100, 200, 4, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentCombatSystem.CombatAction.APPROACH);
        assertThat(plan.executable()).isFalse();
        assertThat(plan.blockedReason()).contains("outside basic attack range");
    }

    @Test
    void attacksCloseMonsterAndFacesTowardTarget() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 20);
        target.put("x", 80);
        target.put("y", 200);

        AgentCombatSystem.CombatPlan plan = system.plan(new AgentCombatSystem.CombatContext(
                target, 100, 200, 4, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentCombatSystem.CombatAction.ATTACK);
        assertThat(plan.executable()).isTrue();
        assertThat(plan.direction()).isZero();
    }

    @Test
    void kiteStyleRetreatsBeforeAttackingWhenMonsterIsTooClose() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 20);
        target.put("x", 110);
        target.put("y", 200);
        target.put("combatStyle", "KITE");

        AgentCombatSystem.CombatPlan plan = system.plan(new AgentCombatSystem.CombatContext(
                target, 100, 200, 4, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentCombatSystem.CombatAction.RETREAT);
        assertThat(plan.executable()).isFalse();
        assertThat(plan.blockedReason()).contains("too close");
        assertThat(plan.direction()).isZero();
    }

    @Test
    void throttlesRepeatedBasicAttacks() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 20);
        target.put("x", 110);
        target.put("y", 200);

        AgentCombatSystem.CombatPlan plan = system.plan(new AgentCombatSystem.CombatContext(
                target, 100, 200, 4, 9_700, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentCombatSystem.CombatAction.ATTACK);
        assertThat(plan.throttled()).isTrue();
        assertThat(plan.executable()).isFalse();
    }
}
