package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterAbilityPointResetTest {

    @Test
    void includesCreationAndLevelUpAbilityPoints() {
        assertEquals(54, Character.calculateLegitimateAbilityPoints(Job.BEGINNER, 10));
    }

    @Test
    void includesExplorerThirdAndFourthJobAdvancementAbilityPoints() {
        assertEquals(614, Character.calculateLegitimateAbilityPoints(Job.HERO, 120));
    }

    @Test
    void includesCygnusLevelAndAdvancementBonuses() {
        assertEquals(698, Character.calculateLegitimateAbilityPoints(Job.DAWNWARRIOR4, 120));
    }

    @Test
    void includesEvanGrowthStageAdvancementBonuses() {
        assertEquals(239, Character.calculateLegitimateAbilityPoints(Job.EVAN4, 45));
    }
}
