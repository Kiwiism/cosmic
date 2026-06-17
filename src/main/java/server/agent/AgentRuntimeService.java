package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final Pattern LOCATED_TARGET_ID_PATTERN = Pattern.compile("\"locatedTarget\"\\s*:\\s*\\{[^}]*\"characterId\"\\s*:\\s*(\\d+)");
    private static final Pattern LOCATED_TARGET_NAME_PATTERN = Pattern.compile("\"locatedTarget\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LOOT_STATE_PATTERN = Pattern.compile("\"lootState\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LOOT_OBJECT_ID_PATTERN = Pattern.compile("\"drop\"\\s*:\\s*\\{[^}]*\"objectId\"\\s*:\\s*(\\d+)");
    private static final Pattern LOOT_ITEM_ID_PATTERN = Pattern.compile("\"drop\"\\s*:\\s*\\{[^}]*\"itemId\"\\s*:\\s*(null|\\d+)");
    private static final Pattern LOOT_QUANTITY_PATTERN = Pattern.compile("\"drop\"\\s*:\\s*\\{[^}]*\"quantity\"\\s*:\\s*(null|-?\\d+)");
    private static final Pattern LOOT_MESO_PATTERN = Pattern.compile("\"drop\"\\s*:\\s*\\{[^}]*\"meso\"\\s*:\\s*(null|-?\\d+)");
    private static final Pattern CHAT_STATE_PATTERN = Pattern.compile("\"chatState\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CHAT_MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern COMBAT_STATE_PATTERN = Pattern.compile("\"combatState\"\\s*:\\s*\"([^\"]*)\"");

    private final AgentRuntimeRepository repository;
    private final AgentControlGuard controlGuard;
    private final AgentSpawnPlanner spawnPlanner;

    public AgentRuntimeService() {
        this(new AgentRuntimeRepository(), new AgentRepository(), new AgentControlGuard());
    }

    AgentRuntimeService(AgentRuntimeRepository repository, AgentRepository agentRepository, AgentControlGuard controlGuard) {
        this.repository = repository;
        this.controlGuard = controlGuard;
        this.spawnPlanner = new AgentSpawnPlanner(agentRepository, controlGuard);
    }

    AgentRuntimeRepository repository() {
        return repository;
    }

    public AgentControlDecision canControl(AgentProfile profile) {
        return controlGuard.canRuntimeControl(profile);
    }

    public AgentSpawnPlan planSpawn(AgentProfile profile) throws SQLException {
        return spawnPlanner.plan(profile);
    }

    public AgentRuntimeSession startSession(AgentProfile profile, int world, int channel, int mapId) throws SQLException {
        AgentRuntimeSession session = repository.startSession(profile, world, channel, mapId);
        repository.logAction(AgentActionLogEntry.lifecycle(profile.id(), session.id(), "Agent runtime session created"));
        return session;
    }

    public void markRunning(AgentRuntimeSession session, String task) throws SQLException {
        repository.updateSessionState(session.id(), AgentRuntimeState.RUNNING, task);
    }

    public void markIdle(AgentRuntimeSession session, String task) throws SQLException {
        repository.updateSessionState(session.id(), AgentRuntimeState.IDLE, task);
    }

    public void heartbeat(AgentRuntimeSession session, String task) throws SQLException {
        repository.heartbeat(session.id(), task);
    }

    public void updateSessionLocation(AgentRuntimeSession session, int world, int channel, int mapId, String task) throws SQLException {
        repository.updateSessionLocation(session.id(), world, channel, mapId, task);
    }

    public void stopSession(AgentRuntimeSession session, String reason) {
        try {
            repository.endSession(session.id(), AgentRuntimeState.STOPPED, reason);
            repository.logAction(AgentActionLogEntry.lifecycle(session.agentProfileId(), session.id(), reason));
        } catch (SQLException e) {
            log.warn("Failed to stop agent runtime session {}", session.id(), e);
        }
    }

    public void logLifecycle(int profileId, long sessionId, Integer world, Integer channel, Integer mapId, String message) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                profileId,
                sessionId,
                "LIFECYCLE",
                AgentActionStatus.OK,
                world,
                channel,
                mapId,
                null,
                null,
                message,
                null
        ));
    }

    public void logPlannedIntent(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            AgentKnowledgeSnapshot knowledge,
            AgentPlan plan,
            String message
    ) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                managed.profileId(),
                managed.session().id(),
                "INTENT_PLAN",
                intent.type() == AgentIntentType.UNKNOWN ? AgentActionStatus.BLOCKED : AgentActionStatus.OK,
                perception.world(),
                perception.channel(),
                perception.mapId(),
                intentTargetType(intent),
                plan.goal() == null ? null : plan.goal().id(),
                message,
                plannedIntentDetailsJson(intent, perception, knowledge, plan)
        ));
    }

    public void logDispatchedIntent(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource,
            AgentIntentDispatchResult result
    ) throws SQLException {
        repository.logAction(new AgentActionLogEntry(
                managed.profileId(),
                managed.session().id(),
                "INTENT_DISPATCH",
                result.status(),
                perception.world(),
                perception.channel(),
                perception.mapId(),
                intentTargetType(intent),
                null,
                result.message(),
                dispatchedIntentDetailsJson(intent, perception, scriptSource, result)
        ));
    }

    public void rememberPilotTick(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception,
            AgentKnowledgeSnapshot knowledge,
            AgentPlan plan
    ) throws SQLException {
        repository.remember(new AgentMemoryEvent(
                managed.profileId(),
                "PILOT_TICK",
                memoryImportance(intent, dispatchResult, perception),
                null,
                null,
                perception.mapId(),
                memorySummary(intent, dispatchResult, perception),
                "{"
                        + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                        + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                        + "\"dispatchStatus\":\"" + escapeJson(dispatchResult.status().name()) + "\","
                        + "\"dispatchMessage\":\"" + escapeJson(dispatchResult.message()) + "\","
                        + "\"dispatchDetails\":" + nullableJsonObject(dispatchResult.detailsJson()) + ","
                        + "\"plan\":" + planDetailsJson(plan) + ","
                        + "\"knowledge\":" + knowledgeDetailsJson(knowledge) + ","
                        + "\"perception\":" + perceptionDetailsJson(perception)
                        + "}"
        ));
        rememberNavigationRoute(managed, intent, dispatchResult, perception);
        rememberCompanionRelationship(managed, intent, dispatchResult, perception);
        rememberLootEconomy(managed, intent, dispatchResult, perception);
        rememberOutboundChat(managed, intent, dispatchResult);
        rememberTargetScan(managed, perception);
        rememberCombatPreview(managed, intent, dispatchResult, perception);
    }

    public void failSession(AgentRuntimeSession session, String reason) {
        try {
            repository.endSession(session.id(), AgentRuntimeState.FAILED, reason);
            repository.logAction(new AgentActionLogEntry(
                    session.agentProfileId(),
                    session.id(),
                    "LIFECYCLE",
                    AgentActionStatus.FAILED,
                    session.world(),
                    session.channel(),
                    session.mapId(),
                    null,
                    null,
                    reason,
                    null
            ));
        } catch (SQLException e) {
            log.warn("Failed to mark agent runtime session {} as failed", session.id(), e);
        }
    }

    private String intentTargetType(AgentIntent intent) {
        return switch (intent.type()) {
            case SAY -> "CHAT";
            case ROAM, MOVE, MOVE_TO_MAP, FOLLOW_CHARACTER, USE_PORTAL -> "NAVIGATION";
            case ATTACK, GRIND -> "COMBAT";
            case LOOT, SHOP, TRADE -> "ECONOMY";
            case NPC -> "NPC";
            case PARTY -> "SOCIAL";
            case USE_ITEM, EQUIP -> "INVENTORY";
            case IDLE, WAIT -> "SELF";
            case UNKNOWN -> "SCRIPT";
        };
    }

    private int memoryImportance(AgentIntent intent, AgentIntentDispatchResult dispatchResult, AgentPerceptionSnapshot perception) {
        int importance = 1;
        if (!perception.available() || dispatchResult.status() == AgentActionStatus.BLOCKED) {
            importance += 1;
        }
        if (perception.monsters() > 0 || perception.players() > 1 || intent.type() != AgentIntentType.IDLE) {
            importance += 1;
        }
        return Math.min(importance, 5);
    }

    private String memorySummary(AgentIntent intent, AgentIntentDispatchResult dispatchResult, AgentPerceptionSnapshot perception) {
        return "Saw "
                + perception.players() + " players, "
                + perception.monsters() + " monsters, "
                + perception.drops() + " drops, planned "
                + intent.type()
                + " and dispatch was "
                + dispatchResult.status();
    }

    private String plannedIntentDetailsJson(
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            AgentKnowledgeSnapshot knowledge,
            AgentPlan plan
    ) {
        return "{"
                + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                + "\"durationMillis\":" + intent.durationMillis() + ","
                + "\"plan\":" + planDetailsJson(plan) + ","
                + "\"knowledge\":" + knowledgeDetailsJson(knowledge) + ","
                + "\"perception\":" + perceptionDetailsJson(perception)
                + "}";
    }

    private String planDetailsJson(AgentPlan plan) {
        return "{"
                + "\"source\":\"" + escapeJson(plan.source()) + "\","
                + "\"reason\":\"" + escapeJson(plan.reason()) + "\","
                + "\"goal\":" + goalDetailsJson(plan.goal())
                + "}";
    }

    private String goalDetailsJson(AgentGoal goal) {
        if (goal == null) {
            return "null";
        }
        return "{"
                + "\"id\":" + goal.id() + ","
                + "\"type\":\"" + escapeJson(goal.goalType()) + "\","
                + "\"priority\":" + goal.priority() + ","
                + "\"status\":\"" + escapeJson(goal.status()) + "\","
                + "\"targetWorld\":" + nullableNumber(goal.targetWorld()) + ","
                + "\"targetChannel\":" + nullableNumber(goal.targetChannel()) + ","
                + "\"targetMap\":" + nullableNumber(goal.targetMap()) + ","
                + "\"targetRef\":\"" + escapeJson(goal.targetRef()) + "\""
                + "}";
    }

    private String knowledgeDetailsJson(AgentKnowledgeSnapshot knowledge) {
        return "{"
                + "\"level\":" + knowledge.level() + ","
                + "\"jobId\":" + knowledge.jobId() + ","
                + "\"meso\":" + knowledge.meso() + ","
                + "\"skills\":" + skillsJson(knowledge.skills()) + ","
                + "\"inventories\":" + inventoriesJson(knowledge.inventories())
                + "}";
    }

    private String skillsJson(List<AgentKnowledgeSnapshot.SkillSummary> skills) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < skills.size(); i++) {
            AgentKnowledgeSnapshot.SkillSummary skill = skills.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{")
                    .append("\"skillId\":").append(skill.skillId()).append(',')
                    .append("\"level\":").append(skill.level()).append(',')
                    .append("\"masterLevel\":").append(skill.masterLevel()).append(',')
                    .append("\"expiration\":").append(skill.expiration())
                    .append("}");
        }
        return builder.append(']').toString();
    }

    private String inventoriesJson(List<AgentKnowledgeSnapshot.InventorySummary> inventories) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < inventories.size(); i++) {
            AgentKnowledgeSnapshot.InventorySummary inventory = inventories.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{")
                    .append("\"type\":\"").append(escapeJson(inventory.type())).append("\",")
                    .append("\"slotLimit\":").append(inventory.slotLimit()).append(',')
                    .append("\"usedSlots\":").append(inventory.usedSlots()).append(',')
                    .append("\"totalQuantity\":").append(inventory.totalQuantity()).append(',')
                    .append("\"sampleItems\":").append(itemsJson(inventory.sampleItems()))
                    .append("}");
        }
        return builder.append(']').toString();
    }

    private String itemsJson(List<AgentKnowledgeSnapshot.ItemStackSummary> items) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            AgentKnowledgeSnapshot.ItemStackSummary item = items.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{")
                    .append("\"itemId\":").append(item.itemId()).append(',')
                    .append("\"quantity\":").append(item.quantity()).append(',')
                    .append("\"position\":").append(item.position())
                    .append("}");
        }
        return builder.append(']').toString();
    }

    private String dispatchedIntentDetailsJson(
            AgentIntent intent,
            AgentPerceptionSnapshot perception,
            String scriptSource,
            AgentIntentDispatchResult dispatchResult
    ) {
        return "{"
                + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                + "\"durationMillis\":" + intent.durationMillis() + ","
                + "\"scriptSource\":\"" + escapeJson(scriptSource) + "\","
                + "\"dispatchMessage\":\"" + escapeJson(dispatchResult.message()) + "\","
                + "\"capability\":\"" + escapeJson(dispatchResult.capability().name()) + "\","
                + "\"policyAllowed\":" + dispatchResult.policyAllowed() + ","
                + "\"gameplayMutated\":" + dispatchResult.gameplayMutated() + ","
                + "\"dryRun\":" + dispatchResult.dryRun() + ","
                + "\"actionDetails\":" + nullableJsonObject(dispatchResult.detailsJson()) + ","
                + "\"perception\":" + perceptionDetailsJson(perception)
                + "}";
    }

    private void rememberNavigationRoute(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception
    ) throws SQLException {
        if (dispatchResult.capability() != AgentIntentCapability.NAVIGATION || dispatchResult.detailsJson() == null) {
            return;
        }

        repository.remember(new AgentMemoryEvent(
                managed.profileId(),
                "NAVIGATION_ROUTE",
                dispatchResult.status() == AgentActionStatus.OK ? 3 : 2,
                null,
                null,
                perception.mapId(),
                "Navigation " + intent.type() + " route state: " + dispatchResult.status(),
                "{"
                        + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                        + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                        + "\"dispatchStatus\":\"" + escapeJson(dispatchResult.status().name()) + "\","
                        + "\"dispatchMessage\":\"" + escapeJson(dispatchResult.message()) + "\","
                        + "\"route\":" + dispatchResult.detailsJson()
                        + "}"
        ));
    }

    private void rememberCompanionRelationship(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception
    ) throws SQLException {
        if (intent.type() != AgentIntentType.FOLLOW_CHARACTER || dispatchResult.detailsJson() == null) {
            return;
        }

        Integer relatedCharacterId = extractLocatedTargetId(dispatchResult.detailsJson());
        if (relatedCharacterId == null) {
            return;
        }

        String targetName = extractLocatedTargetName(dispatchResult.detailsJson());
        repository.upsertCompanionRelationship(
                managed.profileId(),
                relatedCharacterId,
                dispatchResult.status() == AgentActionStatus.OK ? 10 : 5,
                "Follow runtime target " + (targetName == null ? relatedCharacterId : targetName)
                        + " at map " + perception.mapId()
        );
        repository.remember(new AgentMemoryEvent(
                managed.profileId(),
                "COMPANION_TARGET",
                dispatchResult.status() == AgentActionStatus.OK ? 4 : 3,
                relatedCharacterId,
                null,
                perception.mapId(),
                "Companion target " + (targetName == null ? relatedCharacterId : targetName)
                        + " located during follow runtime tick",
                "{"
                        + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                        + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                        + "\"dispatchStatus\":\"" + escapeJson(dispatchResult.status().name()) + "\","
                        + "\"followDetails\":" + nullableJsonObject(dispatchResult.detailsJson())
                        + "}"
        ));
    }

    private void rememberLootEconomy(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception
    ) throws SQLException {
        if (intent.type() != AgentIntentType.LOOT
                || !dispatchResult.gameplayMutated()
                || dispatchResult.detailsJson() == null
                || !"PICKED_UP".equals(extractString(LOOT_STATE_PATTERN, dispatchResult.detailsJson()))) {
            return;
        }

        Integer itemId = extractNullableInteger(LOOT_ITEM_ID_PATTERN, dispatchResult.detailsJson());
        Integer quantity = extractNullableInteger(LOOT_QUANTITY_PATTERN, dispatchResult.detailsJson());
        Integer meso = extractNullableInteger(LOOT_MESO_PATTERN, dispatchResult.detailsJson());
        Integer objectId = extractNullableInteger(LOOT_OBJECT_ID_PATTERN, dispatchResult.detailsJson());
        repository.recordEconomyLedger(
                managed.profileId(),
                managed.session().id(),
                "LOOT_PICKUP",
                itemId,
                quantity == null ? 0 : quantity,
                meso == null ? 0L : meso,
                "MAP_DROP",
                objectId == null ? null : objectId.longValue(),
                null,
                perception.world(),
                perception.channel(),
                perception.mapId(),
                "{"
                        + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                        + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                        + "\"lootDetails\":" + nullableJsonObject(dispatchResult.detailsJson())
                        + "}"
        );
    }

    private void rememberOutboundChat(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult
    ) throws SQLException {
        if (intent.type() != AgentIntentType.SAY
                || !dispatchResult.gameplayMutated()
                || dispatchResult.detailsJson() == null
                || !"SENT".equals(extractString(CHAT_STATE_PATTERN, dispatchResult.detailsJson()))) {
            return;
        }

        String message = unescapeJson(extractString(CHAT_MESSAGE_PATTERN, dispatchResult.detailsJson()));
        if (message == null || message.isBlank()) {
            return;
        }

        repository.recordChatLog(
                managed.profileId(),
                managed.session().id(),
                "MAP_GENERAL",
                "OUTBOUND",
                managed.characterId(),
                null,
                message
        );
    }

    private void rememberTargetScan(AgentManagedCharacter managed, AgentPerceptionSnapshot perception) throws SQLException {
        if (!perception.available()) {
            return;
        }

        repository.remember(new AgentMemoryEvent(
                managed.profileId(),
                "TARGET_SCAN",
                perception.monsters() > 0 || perception.drops() > 0 || perception.npcs() > 0 ? 3 : 1,
                null,
                null,
                perception.mapId(),
                "Nearest targets: "
                        + targetLabel("monster", firstVisible(perception.nearbyMonsters())) + ", "
                        + targetLabel("drop", firstVisible(perception.nearbyDrops())) + ", "
                        + targetLabel("npc", firstVisible(perception.nearbyNpcs())),
                "{"
                        + "\"world\":" + perception.world() + ","
                        + "\"channel\":" + perception.channel() + ","
                        + "\"mapId\":" + perception.mapId() + ","
                        + "\"position\":{\"x\":" + perception.x() + ",\"y\":" + perception.y() + "},"
                        + "\"counts\":{"
                        + "\"players\":" + perception.players() + ","
                        + "\"monsters\":" + perception.monsters() + ","
                        + "\"drops\":" + perception.drops() + ","
                        + "\"npcs\":" + perception.npcs() + ","
                        + "\"reactors\":" + perception.reactors()
                        + "},"
                        + "\"nearest\":{"
                        + "\"player\":" + visibleObjectOrNull(firstVisible(perception.nearbyPlayers())) + ","
                        + "\"monster\":" + visibleObjectOrNull(firstVisible(perception.nearbyMonsters())) + ","
                        + "\"drop\":" + visibleObjectOrNull(firstVisible(perception.nearbyDrops())) + ","
                        + "\"npc\":" + visibleObjectOrNull(firstVisible(perception.nearbyNpcs())) + ","
                        + "\"reactor\":" + visibleObjectOrNull(firstVisible(perception.nearbyReactors()))
                        + "}"
                        + "}"
        ));
    }

    private AgentPerceptionSnapshot.AgentVisibleObject firstVisible(List<AgentPerceptionSnapshot.AgentVisibleObject> objects) {
        return objects == null || objects.isEmpty() ? null : objects.get(0);
    }

    private String visibleObjectOrNull(AgentPerceptionSnapshot.AgentVisibleObject object) {
        return object == null ? "null" : visibleObjectJson(object);
    }

    private String targetLabel(String label, AgentPerceptionSnapshot.AgentVisibleObject object) {
        if (object == null) {
            return label + " none";
        }
        String name = object.name() == null || object.name().isBlank()
                ? String.valueOf(object.templateId() == null ? object.objectId() : object.templateId())
                : object.name();
        return label + " " + name + " at " + object.distanceSq();
    }

    private void rememberCombatPreview(
            AgentManagedCharacter managed,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception
    ) throws SQLException {
        if (dispatchResult.capability() != AgentIntentCapability.COMBAT || dispatchResult.detailsJson() == null) {
            return;
        }

        String combatState = extractString(COMBAT_STATE_PATTERN, dispatchResult.detailsJson());
        repository.remember(new AgentMemoryEvent(
                managed.profileId(),
                "COMBAT_PREVIEW",
                dispatchResult.status() == AgentActionStatus.OK ? 3 : 2,
                null,
                null,
                perception.mapId(),
                "Combat " + intent.type() + " state: " + (combatState == null ? dispatchResult.status() : combatState),
                "{"
                        + "\"intent\":\"" + escapeJson(intent.type().name()) + "\","
                        + "\"argument\":\"" + escapeJson(intent.argument()) + "\","
                        + "\"dispatchStatus\":\"" + escapeJson(dispatchResult.status().name()) + "\","
                        + "\"dispatchMessage\":\"" + escapeJson(dispatchResult.message()) + "\","
                        + "\"combat\":" + nullableJsonObject(dispatchResult.detailsJson())
                        + "}"
        ));
    }

    private Integer extractLocatedTargetId(String detailsJson) {
        Matcher matcher = LOCATED_TARGET_ID_PATTERN.matcher(detailsJson);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String extractLocatedTargetName(String detailsJson) {
        Matcher matcher = LOCATED_TARGET_NAME_PATTERN.matcher(detailsJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractString(Pattern pattern, String detailsJson) {
        Matcher matcher = pattern.matcher(detailsJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String unescapeJson(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaping) {
                builder.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> current;
                });
                escaping = false;
            } else if (current == '\\') {
                escaping = true;
            } else {
                builder.append(current);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private Integer extractNullableInteger(Pattern pattern, String detailsJson) {
        Matcher matcher = pattern.matcher(detailsJson);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        if (value == null || "null".equals(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String perceptionDetailsJson(AgentPerceptionSnapshot perception) {
        return "{"
                + "\"available\":" + perception.available() + ","
                + "\"world\":" + perception.world() + ","
                + "\"channel\":" + perception.channel() + ","
                + "\"mapId\":" + perception.mapId() + ","
                + "\"position\":{\"x\":" + perception.x() + ",\"y\":" + perception.y() + "},"
                + "\"counts\":{"
                + "\"players\":" + perception.players() + ","
                + "\"monsters\":" + perception.monsters() + ","
                + "\"drops\":" + perception.drops() + ","
                + "\"npcs\":" + perception.npcs() + ","
                + "\"reactors\":" + perception.reactors()
                + "},"
                + "\"nearby\":{"
                + "\"players\":" + visibleObjectsJson(perception.nearbyPlayers()) + ","
                + "\"monsters\":" + visibleObjectsJson(perception.nearbyMonsters()) + ","
                + "\"drops\":" + visibleObjectsJson(perception.nearbyDrops()) + ","
                + "\"npcs\":" + visibleObjectsJson(perception.nearbyNpcs()) + ","
                + "\"reactors\":" + visibleObjectsJson(perception.nearbyReactors())
                + "},"
                + "\"message\":\"" + escapeJson(perception.message()) + "\""
                + "}";
    }

    private String visibleObjectsJson(List<AgentPerceptionSnapshot.AgentVisibleObject> objects) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < objects.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(visibleObjectJson(objects.get(i)));
        }
        return builder.append(']').toString();
    }

    private String visibleObjectJson(AgentPerceptionSnapshot.AgentVisibleObject object) {
        return "{"
                + "\"type\":\"" + escapeJson(object.type()) + "\","
                + "\"objectId\":" + object.objectId() + ","
                + "\"templateId\":" + nullableNumber(object.templateId()) + ","
                + "\"name\":\"" + escapeJson(object.name()) + "\","
                + "\"x\":" + object.x() + ","
                + "\"y\":" + object.y() + ","
                + "\"distanceSq\":" + object.distanceSq() + ","
                + "\"hp\":" + nullableNumber(object.hp()) + ","
                + "\"maxHp\":" + nullableNumber(object.maxHp()) + ","
                + "\"level\":" + nullableNumber(object.level()) + ","
                + "\"quantity\":" + nullableNumber(object.quantity()) + ","
                + "\"meso\":" + nullableNumber(object.meso()) + ","
                + "\"alive\":" + nullableBoolean(object.alive()) + ","
                + "\"state\":" + nullableNumber(object.state())
                + "}";
    }

    private String nullableNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String nullableBoolean(Boolean value) {
        return value == null ? "null" : value.toString();
    }

    private String nullableJsonObject(String value) {
        return value == null || value.isBlank() ? "null" : value;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
