package server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentScriptRunnerTest {
    private final AgentScriptRunner runner = new AgentScriptRunner();

    @Test
    void parsesRepeatBlocksIntoRepeatedIntents() {
        List<AgentIntent> intents = runner.parse("""
                REPEAT 3 WAIT 1
                SAY hello
                """);

        assertEquals(4, intents.size());
        assertEquals(AgentIntentType.WAIT, intents.get(0).type());
        assertEquals(AgentIntentType.WAIT, intents.get(1).type());
        assertEquals(AgentIntentType.WAIT, intents.get(2).type());
        assertEquals(AgentIntentType.SAY, intents.get(3).type());
        assertEquals(1_000L, intents.get(0).durationMillis());
    }

    @Test
    void parsesCompactRepeatAndInlineComments() {
        List<AgentIntent> intents = runner.parse("""
                2x SAY hello there # staff note
                # skipped
                LOOT nearest
                """);

        assertEquals(3, intents.size());
        assertEquals(AgentIntentType.SAY, intents.get(0).type());
        assertEquals("hello there", intents.get(0).argument());
        assertEquals(AgentIntentType.SAY, intents.get(1).type());
        assertEquals(AgentIntentType.LOOT, intents.get(2).type());
    }

    @Test
    void capsRepeatsToProtectRuntimeExpansion() {
        List<AgentIntent> intents = runner.parse("REPEAT 200 IDLE 1");

        assertEquals(50, intents.size());
        assertEquals(AgentIntentType.IDLE, intents.get(0).type());
    }

    @Test
    void parsesSkillReadinessAliases() {
        List<AgentIntent> intents = runner.parse("""
                SKILL attack
                CAST 1001005
                """);

        assertEquals(2, intents.size());
        assertEquals(AgentIntentType.SKILL, intents.get(0).type());
        assertEquals("attack", intents.get(0).argument());
        assertEquals(AgentIntentType.SKILL, intents.get(1).type());
        assertEquals("1001005", intents.get(1).argument());
    }
}
