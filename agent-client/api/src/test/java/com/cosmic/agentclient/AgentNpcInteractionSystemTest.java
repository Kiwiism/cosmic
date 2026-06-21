package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNpcInteractionSystemTest {
    private final AgentNpcInteractionSystem system = new AgentNpcInteractionSystem();

    @Test
    void opensVisibleNpcWhenCloseEnoughAndNoConversationIsActive() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 10);
        target.put("npcId", 2101);
        target.put("x", 100);
        target.put("y", 200);

        AgentNpcInteractionSystem.NpcPlan plan = system.plan(new AgentNpcInteractionSystem.NpcContext(
                target, 120, 200, false, 0, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentNpcInteractionSystem.NpcAction.OPEN);
        assertThat(plan.executable()).isTrue();
        assertThat(plan.objectId()).isEqualTo(10);
    }

    @Test
    void continuesActiveNpcConversationWithConfiguredSelection() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 10);
        target.put("x", 100);
        target.put("y", 200);
        target.put("messageType", 4);
        target.put("selection", 2);

        AgentNpcInteractionSystem.NpcPlan plan = system.plan(new AgentNpcInteractionSystem.NpcContext(
                target, 100, 200, true, 0, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentNpcInteractionSystem.NpcAction.CONTINUE);
        assertThat(plan.messageType()).isEqualTo(4);
        assertThat(plan.selection()).isEqualTo(2);
    }

    @Test
    void blocksOpenWhenNpcIsTooFarSoNavigationCanApproachFirst() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 10);
        target.put("npcId", 2101);
        target.put("x", 600);
        target.put("y", 200);
        target.put("interactionRange", 120);

        AgentNpcInteractionSystem.NpcPlan plan = system.plan(new AgentNpcInteractionSystem.NpcContext(
                target, 100, 200, false, 0, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentNpcInteractionSystem.NpcAction.NONE);
        assertThat(plan.executable()).isFalse();
        assertThat(plan.blockedReason()).contains("not close enough");
    }

    @Test
    void sendsTextWhenQuestDialogRequiresTypedInput() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("objectId", 10);
        target.put("x", 100);
        target.put("y", 200);
        target.put("messageType", 2);
        target.put("action", 1);
        target.put("text", "Mai");

        AgentNpcInteractionSystem.NpcPlan plan = system.plan(new AgentNpcInteractionSystem.NpcContext(
                target, 100, 200, true, 0, 0, 10_000
        ));

        assertThat(plan.action()).isEqualTo(AgentNpcInteractionSystem.NpcAction.TEXT);
        assertThat(plan.executable()).isTrue();
        assertThat(plan.text()).isEqualTo("Mai");
        assertThat(plan.continueAction()).isEqualTo(1);
    }
}
