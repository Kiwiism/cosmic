package server.agent.actions;

import client.Character;
import client.Character.SkillEntry;
import client.Skill;
import server.StatEffect;
import server.agent.AgentActionStatus;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentPerceptionSnapshot;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

public final class AgentCombatActionAdapter implements AgentActionAdapter {
    private static final int MAX_APPROACH_STEP_X = 180;
    private static final int MAX_APPROACH_STEP_Y = 120;
    private static final int ATTACK_RANGE_SQ = 150 * 150;
    private static final int MAX_BASIC_ATTACK_DAMAGE = 50_000;
    private static final int MAX_SKILL_ATTACK_DAMAGE = 120_000;

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.COMBAT;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type != AgentIntentType.ATTACK && type != AgentIntentType.GRIND) {
            return AgentActionResult.blockedByRuntime(capability(), type + " reached the combat adapter unexpectedly");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot select a combat target without an available perception snapshot");
        }

        Optional<AgentPerceptionSnapshot.AgentVisibleObject> target = selectMonster(context);
        if (target.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No alive visible monster is available for " + type,
                    combatDetailsJson(context, null, "NO_TARGET", null, null)
            );
        }

        AgentPerceptionSnapshot.AgentVisibleObject monster = target.get();
        if (monster.distanceSq() <= ATTACK_RANGE_SQ) {
            return basicAttack(context, monster);
        }

        AgentActionResult approach = approachMonster(context, monster);
        return new AgentActionResult(
                approach.status(),
                capability(),
                approach.gameplayMutated()
                        ? "Moved toward combat target " + monsterLabel(monster)
                        : "Combat target " + monsterLabel(monster) + " did not require movement",
                approach.policyAllowed(),
                approach.gameplayMutated(),
                approach.dryRun(),
                combatDetailsJson(context, monster, "APPROACHING_TARGET", approach.detailsJson(), null),
                approach.completedAt()
        );
    }

    private AgentActionResult basicAttack(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject visibleMonster) {
        Character character = context.managed().character();
        MapleMap map = character == null ? null : character.getMap();
        if (character == null || map == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot attack without an attached character and map");
        }

        Monster monster = map.getMonsterByOid(visibleMonster.objectId());
        if (monster == null || !monster.isAlive()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat target " + monsterLabel(visibleMonster) + " is no longer alive",
                    combatDetailsJson(context, visibleMonster, "TARGET_GONE", null, null)
            );
        }
        if (monster.isBoss()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Agent basic attacks do not target bosses yet",
                    combatDetailsJson(context, visibleMonster, "BOSS_BLOCKED", null, attackDetailsJson(monster, 0, 0, false, 0))
            );
        }

        Optional<Map.Entry<Skill, SkillEntry>> skill = selectAttackSkill(character);
        if (skill.isPresent()) {
            return skillAttack(context, visibleMonster, monster, skill.get());
        }

        int beforeHp = monster.getHp();
        int damage = calculateBasicAttackDamage(character, beforeHp);
        if (damage <= 0) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Could not calculate a positive basic attack damage value",
                    combatDetailsJson(context, visibleMonster, "NO_DAMAGE", null, attackDetailsJson(monster, beforeHp, beforeHp, false, 0))
            );
        }

        map.broadcastMessage(character, PacketCreator.damageMonster(monster.getObjectId(), damage), true);
        boolean applied = map.damageMonster(character, monster, damage, (short) 0);
        int afterHp = monster.isAlive() ? monster.getHp() : 0;
        String state = applied ? "BASIC_ATTACK_APPLIED" : "BASIC_ATTACK_REJECTED";
        return new AgentActionResult(
                applied ? AgentActionStatus.OK : AgentActionStatus.BLOCKED,
                capability(),
                applied
                        ? "Basic attacked " + monsterLabel(visibleMonster) + " for " + damage + " damage"
                        : "Basic attack against " + monsterLabel(visibleMonster) + " was rejected by the map",
                true,
                applied,
                false,
                combatDetailsJson(context, visibleMonster, state, null, attackDetailsJson(monster, beforeHp, afterHp, applied, damage)),
                java.time.Instant.now()
        );
    }

    private AgentActionResult skillAttack(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject visibleMonster,
            Monster monster,
            Map.Entry<Skill, SkillEntry> selectedSkill
    ) {
        Character character = context.managed().character();
        Skill skill = selectedSkill.getKey();
        SkillEntry entry = selectedSkill.getValue();
        StatEffect effect = effect(skill, entry);
        if (effect == null) {
            return basicAttackFallback(context, visibleMonster, monster, "Selected skill " + skill.getId() + " has no usable effect");
        }

        int mpBefore = character.getMp();
        int beforeHp = monster.getHp();
        int damage = calculateSkillAttackDamage(character, beforeHp, skill, entry, effect);
        if (damage <= 0) {
            return basicAttackFallback(context, visibleMonster, monster, "Selected skill " + skill.getId() + " did not calculate positive damage");
        }

        if (effect.getMpCon() > 0) {
            character.addMP(-effect.getMpCon());
        }

        mapFor(character).broadcastMessage(character, PacketCreator.damageMonster(monster.getObjectId(), damage), true);
        boolean applied = mapFor(character).damageMonster(character, monster, damage, animationDelay(skill));
        int afterHp = monster.isAlive() ? monster.getHp() : 0;
        String state = applied ? "SKILL_ATTACK_APPLIED" : "SKILL_ATTACK_REJECTED";
        return new AgentActionResult(
                applied ? AgentActionStatus.OK : AgentActionStatus.BLOCKED,
                capability(),
                applied
                        ? "Used skill " + skill.getId() + " on " + monsterLabel(visibleMonster) + " for " + damage + " damage"
                        : "Skill " + skill.getId() + " against " + monsterLabel(visibleMonster) + " was rejected by the map",
                true,
                applied,
                false,
                combatDetailsJson(
                        context,
                        visibleMonster,
                        state,
                        null,
                        skillAttackDetailsJson(monster, beforeHp, afterHp, applied, damage, skill, entry, effect, mpBefore, character.getMp())
                ),
                java.time.Instant.now()
        );
    }

    private AgentActionResult basicAttackFallback(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject visibleMonster,
            Monster monster,
            String fallbackReason
    ) {
        int beforeHp = monster.getHp();
        int damage = calculateBasicAttackDamage(context.managed().character(), beforeHp);
        if (damage <= 0) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    fallbackReason,
                    combatDetailsJson(context, visibleMonster, "SKILL_FALLBACK_NO_DAMAGE", null, attackDetailsJson(monster, beforeHp, beforeHp, false, 0))
            );
        }

        mapFor(context.managed().character()).broadcastMessage(context.managed().character(), PacketCreator.damageMonster(monster.getObjectId(), damage), true);
        boolean applied = mapFor(context.managed().character()).damageMonster(context.managed().character(), monster, damage, (short) 0);
        int afterHp = monster.isAlive() ? monster.getHp() : 0;
        return new AgentActionResult(
                applied ? AgentActionStatus.OK : AgentActionStatus.BLOCKED,
                capability(),
                "Fell back to basic attack after skill selection: " + fallbackReason,
                true,
                applied,
                false,
                combatDetailsJson(context, visibleMonster, "SKILL_FALLBACK_BASIC_ATTACK", null, attackDetailsJson(monster, beforeHp, afterHp, applied, damage)),
                java.time.Instant.now()
        );
    }

    private int calculateBasicAttackDamage(Character character, int monsterHp) {
        int totalWatk = Math.max(1, character.getTotalWatk());
        int maxBaseDamage = Math.max(1, character.calculateMaxBaseDamage(totalWatk));
        int conservativeDamage = Math.max(1, maxBaseDamage / 2);
        return Math.min(Math.min(conservativeDamage, MAX_BASIC_ATTACK_DAMAGE), monsterHp);
    }

    private int calculateSkillAttackDamage(Character character, int monsterHp, Skill skill, SkillEntry entry, StatEffect effect) {
        int attackCount = Math.max(1, effect.getAttackCount());
        int skillDamagePercent = Math.max(100, effect.getDamage());
        int totalAttack = Math.max(1, Math.max(character.getTotalWatk(), character.getTotalMagic()));
        int baseDamage = Math.max(1, character.calculateMaxBaseDamage(totalAttack));
        int conservativeDamage = Math.max(1, baseDamage * skillDamagePercent / 100);
        int levelBonus = Math.max(0, entry.skillevel - 1) * 3;
        int capped = Math.min(conservativeDamage + levelBonus, MAX_SKILL_ATTACK_DAMAGE);
        int multiHitAdjusted = Math.max(1, capped / attackCount);
        return Math.min(multiHitAdjusted, monsterHp);
    }

    private Optional<Map.Entry<Skill, SkillEntry>> selectAttackSkill(Character character) {
        return character.getSkills().entrySet().stream()
                .filter(entry -> entry.getValue().skillevel > 0)
                .filter(entry -> isUsableAttackSkill(character, entry.getKey(), entry.getValue()))
                .max(Comparator.comparingInt(entry -> attackSkillScore(entry.getKey(), entry.getValue())));
    }

    private boolean isUsableAttackSkill(Character character, Skill skill, SkillEntry entry) {
        StatEffect effect = effect(skill, entry);
        return effect != null
                && isLikelyAttack(skill, effect)
                && effect.getCooldown() <= 0
                && effect.getHpCon() <= 0
                && effect.getMpCon() <= character.getMp();
    }

    private boolean isLikelyAttack(Skill skill, StatEffect effect) {
        return effect.getDamage() > 0;
    }

    private int attackSkillScore(Skill skill, SkillEntry entry) {
        StatEffect effect = effect(skill, entry);
        if (effect == null) {
            return 0;
        }
        int damage = Math.max(1, effect.getDamage());
        int attackCount = Math.max(1, effect.getAttackCount());
        int mobCount = Math.max(1, effect.getMobCount());
        return damage * attackCount * mobCount + entry.skillevel;
    }

    private StatEffect effect(Skill skill, SkillEntry entry) {
        try {
            return entry.skillevel <= 0 || entry.skillevel > skill.getMaxLevel() ? null : skill.getEffect(entry.skillevel);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private short animationDelay(Skill skill) {
        return (short) clamp(skill.getAnimationTime(), 0, Short.MAX_VALUE);
    }

    private MapleMap mapFor(Character character) {
        return character.getMap();
    }

    private Optional<AgentPerceptionSnapshot.AgentVisibleObject> selectMonster(AgentActionContext context) {
        String target = context.intent().argument();
        return context.perception().nearbyMonsters().stream()
                .filter(monster -> Boolean.TRUE.equals(monster.alive()))
                .filter(monster -> matchesTarget(monster, target))
                .min(Comparator.comparingLong(AgentPerceptionSnapshot.AgentVisibleObject::distanceSq));
    }

    private boolean matchesTarget(AgentPerceptionSnapshot.AgentVisibleObject monster, String target) {
        if (target == null || target.isBlank() || "nearest monster".equalsIgnoreCase(target.trim())) {
            return true;
        }
        String trimmed = target.trim();
        if (monster.name() != null && monster.name().equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (monster.templateId() != null && String.valueOf(monster.templateId()).equals(trimmed)) {
            return true;
        }
        return String.valueOf(monster.objectId()).equals(trimmed);
    }

    private AgentActionResult approachMonster(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject monster) {
        MapleMap map = context.managed().character().getMap();
        Character character = context.managed().character();
        if (map == null || character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot approach combat target without an attached map");
        }

        Point start = character.getPosition();
        Point target = new Point(monster.x(), monster.y());
        Point step = boundedStep(start, target);
        Point grounded = map.getGroundBelow(step);
        Point destination = grounded == null ? step : grounded;
        long before = distanceSq(start, target);
        long after = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat approach is stuck at " + pointLabel(start),
                    movementDetailsJson(context, start, destination, target, "STUCK", grounded != null)
            );
        }
        if (after >= before) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat approach could not reduce distance to " + monsterLabel(monster),
                    movementDetailsJson(context, start, destination, target, "NO_PROGRESS", grounded != null)
            );
        }

        map.movePlayer(character, destination);
        return AgentActionResult.ok(
                capability(),
                "Approached combat target " + monsterLabel(monster),
                true,
                movementDetailsJson(context, start, destination, target, "MOVED", grounded != null)
        );
    }

    private Point boundedStep(Point start, Point target) {
        int dx = clamp(target.x - start.x, -MAX_APPROACH_STEP_X, MAX_APPROACH_STEP_X);
        int dy = clamp(target.y - start.y, -MAX_APPROACH_STEP_Y, MAX_APPROACH_STEP_Y);
        return new Point(start.x + dx, start.y + dy);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long distanceSq(Point a, Point b) {
        long dx = (long) a.x - b.x;
        long dy = (long) a.y - b.y;
        return dx * dx + dy * dy;
    }

    private String combatDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject monster,
            String state,
            String movementJson,
            String attackJson
    ) {
        return "{"
                + "\"combatState\":\"" + state + "\","
                + "\"intent\":\"" + context.intent().type().name() + "\","
                + "\"argument\":\"" + escapeJson(context.intent().argument()) + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"attackRangeSq\":" + ATTACK_RANGE_SQ + ","
                + "\"target\":" + (monster == null ? "null" : monsterJson(monster)) + ","
                + "\"movement\":" + (movementJson == null ? "null" : movementJson) + ","
                + "\"attack\":" + (attackJson == null ? "null" : attackJson)
                + "}";
    }

    private String attackDetailsJson(Monster monster, int beforeHp, int afterHp, boolean applied, int damage) {
        return "{"
                + "\"action\":\"BASIC_ATTACK\","
                + "\"monsterObjectId\":" + monster.getObjectId() + ","
                + "\"monsterId\":" + monster.getId() + ","
                + "\"boss\":" + monster.isBoss() + ","
                + "\"damage\":" + damage + ","
                + "\"maxDamageCap\":" + MAX_BASIC_ATTACK_DAMAGE + ","
                + "\"hpBefore\":" + beforeHp + ","
                + "\"hpAfter\":" + afterHp + ","
                + "\"applied\":" + applied
                + "}";
    }

    private String skillAttackDetailsJson(
            Monster monster,
            int beforeHp,
            int afterHp,
            boolean applied,
            int damage,
            Skill skill,
            SkillEntry entry,
            StatEffect effect,
            int mpBefore,
            int mpAfter
    ) {
        return "{"
                + "\"action\":\"SKILL_ATTACK\","
                + "\"monsterObjectId\":" + monster.getObjectId() + ","
                + "\"monsterId\":" + monster.getId() + ","
                + "\"boss\":" + monster.isBoss() + ","
                + "\"skillId\":" + skill.getId() + ","
                + "\"skillLevel\":" + entry.skillevel + ","
                + "\"skillAnimationTime\":" + skill.getAnimationTime() + ","
                + "\"mpBefore\":" + mpBefore + ","
                + "\"mpAfter\":" + mpAfter + ","
                + "\"mpCon\":" + effect.getMpCon() + ","
                + "\"effectDamage\":" + effect.getDamage() + ","
                + "\"attackCount\":" + effect.getAttackCount() + ","
                + "\"mobCount\":" + effect.getMobCount() + ","
                + "\"damage\":" + damage + ","
                + "\"maxDamageCap\":" + MAX_SKILL_ATTACK_DAMAGE + ","
                + "\"hpBefore\":" + beforeHp + ","
                + "\"hpAfter\":" + afterHp + ","
                + "\"applied\":" + applied
                + "}";
    }

    private String movementDetailsJson(
            AgentActionContext context,
            Point from,
            Point to,
            Point target,
            String state,
            boolean grounded
    ) {
        long before = distanceSq(from, target);
        long after = distanceSq(to, target);
        return "{"
                + "\"movementState\":\"" + state + "\","
                + "\"action\":\"APPROACH_COMBAT_TARGET\","
                + "\"world\":" + context.managed().client().getWorld() + ","
                + "\"channel\":" + context.managed().client().getChannel() + ","
                + "\"mapId\":" + context.managed().character().getMapId() + ","
                + "\"from\":{\"x\":" + from.x + ",\"y\":" + from.y + "},"
                + "\"to\":{\"x\":" + to.x + ",\"y\":" + to.y + "},"
                + "\"target\":{\"x\":" + target.x + ",\"y\":" + target.y + "},"
                + "\"stepLimit\":{\"x\":" + MAX_APPROACH_STEP_X + ",\"y\":" + MAX_APPROACH_STEP_Y + "},"
                + "\"grounded\":" + grounded + ","
                + "\"progressed\":" + (after < before) + ","
                + "\"distanceSqBefore\":" + before + ","
                + "\"distanceSqAfter\":" + after
                + "}";
    }

    private String monsterJson(AgentPerceptionSnapshot.AgentVisibleObject monster) {
        return "{"
                + "\"objectId\":" + monster.objectId() + ","
                + "\"monsterId\":" + nullableNumber(monster.templateId()) + ","
                + "\"name\":\"" + escapeJson(monster.name()) + "\","
                + "\"position\":{\"x\":" + monster.x() + ",\"y\":" + monster.y() + "},"
                + "\"distanceSq\":" + monster.distanceSq() + ","
                + "\"hp\":" + nullableNumber(monster.hp()) + ","
                + "\"maxHp\":" + nullableNumber(monster.maxHp()) + ","
                + "\"level\":" + nullableNumber(monster.level()) + ","
                + "\"alive\":" + nullableBoolean(monster.alive())
                + "}";
    }

    private String monsterLabel(AgentPerceptionSnapshot.AgentVisibleObject monster) {
        if (monster.name() != null && !monster.name().isBlank()) {
            return monster.name();
        }
        if (monster.templateId() != null) {
            return String.valueOf(monster.templateId());
        }
        return String.valueOf(monster.objectId());
    }

    private String pointLabel(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private String nullableNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String nullableBoolean(Boolean value) {
        return value == null ? "null" : value.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
