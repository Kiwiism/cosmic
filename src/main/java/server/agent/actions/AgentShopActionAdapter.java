package server.agent.actions;

import client.Character;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.StatEffect;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentPerceptionSnapshot;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AgentShopActionAdapter implements AgentActionAdapter {
    private static final int MAX_APPROACH_STEP_X = 180;
    private static final int MAX_APPROACH_STEP_Y = 120;
    private static final int SHOP_RANGE_SQ = 120 * 120;

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.SHOP;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type != AgentIntentType.SHOP) {
            return AgentActionResult.blockedByRuntime(capability(), type + " reached the shop adapter unexpectedly");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot select a shop without an available perception snapshot");
        }

        Optional<ShopTarget> target = selectShop(context);
        if (target.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No visible shop NPC is available for " + type,
                    shopDetailsJson(context, null, null, "NO_TARGET", null)
            );
        }

        ShopTarget shopTarget = target.get();
        AgentPerceptionSnapshot.AgentVisibleObject npc = shopTarget.npc();
        if (npc.distanceSq() <= SHOP_RANGE_SQ) {
            if (isRecoveryTarget(context.intent().argument())) {
                return buyRecoveryItem(context, shopTarget);
            }

            String state = isRecoveryTarget(context.intent().argument()) ? "RECOVERY_SHOP_READY" : "SHOP_READY";
            return AgentActionResult.ok(
                    capability(),
                    state.equals("RECOVERY_SHOP_READY")
                            ? "Shop " + shopTarget.shop().getId() + " at NPC " + npcLabel(npc) + " has recovery items ready for future purchase"
                            : "Shop " + shopTarget.shop().getId() + " at NPC " + npcLabel(npc) + " is ready for future shop interaction",
                    false,
                    shopDetailsJson(context, npc, shopTarget.shop(), state, null)
            );
        }

        AgentActionResult approach = approachNpc(context, npc);
        return new AgentActionResult(
                approach.status(),
                capability(),
                approach.gameplayMutated()
                        ? "Moved toward shop NPC " + npcLabel(npc)
                        : "Shop NPC " + npcLabel(npc) + " did not require movement",
                approach.policyAllowed(),
                approach.gameplayMutated(),
                approach.dryRun(),
                shopDetailsJson(context, npc, shopTarget.shop(), "APPROACHING_SHOP", approach.detailsJson()),
                approach.completedAt()
        );
    }

    private AgentActionResult buyRecoveryItem(AgentActionContext context, ShopTarget shopTarget) {
        Character character = context.managed().character();
        if (character == null || character.getClient() == null) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Cannot buy recovery items without an attached client",
                    shopDetailsJson(context, shopTarget.npc(), shopTarget.shop(), "BUY_REJECTED", null, null)
            );
        }

        Optional<ShopSelection> selection = selectRecoveryPurchase(shopTarget.shop(), context.intent().argument(), character.getMeso());
        if (selection.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No affordable recovery item is available in shop " + shopTarget.shop().getId(),
                    shopDetailsJson(context, shopTarget.npc(), shopTarget.shop(), "NO_AFFORDABLE_RECOVERY", null, null)
            );
        }

        ShopSelection purchase = selection.get();
        int itemId = purchase.item().getItemId();
        int beforeMeso = character.getMeso();
        int beforeQuantity = character.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
        shopTarget.shop().buy(character.getClient(), purchase.slot(), itemId, (short) 1);
        int afterQuantity = character.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
        int afterMeso = character.getMeso();
        boolean bought = afterQuantity > beforeQuantity && afterMeso < beforeMeso;
        String purchaseJson = purchaseJson(context, purchase, beforeQuantity, afterQuantity, beforeMeso, afterMeso, bought);

        if (!bought) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Shop rejected recovery purchase for " + itemLabel(itemId),
                    shopDetailsJson(context, shopTarget.npc(), shopTarget.shop(), "BUY_REJECTED", null, purchaseJson)
            );
        }

        return AgentActionResult.ok(
                capability(),
                "Bought one recovery item " + itemLabel(itemId) + " from shop " + shopTarget.shop().getId(),
                true,
                shopDetailsJson(context, shopTarget.npc(), shopTarget.shop(), "RECOVERY_BOUGHT", null, purchaseJson)
        );
    }

    private Optional<ShopTarget> selectShop(AgentActionContext context) {
        String target = context.intent().argument();
        return context.perception().nearbyNpcs().stream()
                .filter(npc -> matchesTarget(npc, target))
                .map(npc -> {
                    Shop shop = npc.templateId() == null ? null : ShopFactory.getInstance().getShopForNPC(npc.templateId());
                    return shop == null ? null : new ShopTarget(npc, shop);
                })
                .filter(shopTarget -> shopTarget != null)
                .filter(shopTarget -> !isRecoveryTarget(target) || hasRecoveryCandidate(shopTarget.shop(), target))
                .min(Comparator.comparingLong(shopTarget -> shopTarget.npc().distanceSq()));
    }

    private boolean matchesTarget(AgentPerceptionSnapshot.AgentVisibleObject npc, String target) {
        if (target == null || target.isBlank() || "nearest".equalsIgnoreCase(target.trim()) || isRecoveryTarget(target)) {
            return true;
        }
        String trimmed = target.trim();
        if (npc.name() != null && npc.name().equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (npc.templateId() != null && String.valueOf(npc.templateId()).equals(trimmed)) {
            return true;
        }
        return String.valueOf(npc.objectId()).equals(trimmed);
    }

    private AgentActionResult approachNpc(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject npc) {
        MapleMap map = context.managed().character().getMap();
        Character character = context.managed().character();
        if (map == null || character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot approach shop NPC without an attached map");
        }

        Point start = character.getPosition();
        Point target = new Point(npc.x(), npc.y());
        Point step = boundedStep(start, target);
        Point grounded = map.getGroundBelow(step);
        Point destination = grounded == null ? step : grounded;
        long before = distanceSq(start, target);
        long after = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Shop approach is stuck at " + pointLabel(start),
                    movementDetailsJson(context, start, destination, target, "STUCK", grounded != null)
            );
        }
        if (after >= before) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Shop approach could not reduce distance to " + npcLabel(npc),
                    movementDetailsJson(context, start, destination, target, "NO_PROGRESS", grounded != null)
            );
        }

        map.movePlayer(character, destination);
        return AgentActionResult.ok(
                capability(),
                "Approached shop NPC " + npcLabel(npc),
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

    private String shopDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject npc,
            Shop shop,
            String state,
            String movementJson
    ) {
        return shopDetailsJson(context, npc, shop, state, movementJson, null);
    }

    private String shopDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject npc,
            Shop shop,
            String state,
            String movementJson,
            String purchaseJson
    ) {
        return "{"
                + "\"shopState\":\"" + state + "\","
                + "\"intent\":\"" + context.intent().type().name() + "\","
                + "\"argument\":\"" + escapeJson(context.intent().argument()) + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"shopRangeSq\":" + SHOP_RANGE_SQ + ","
                + "\"shop\":" + shopJson(context, shop) + ","
                + "\"target\":" + (npc == null ? "null" : npcJson(npc)) + ","
                + "\"purchase\":" + (purchaseJson == null ? "null" : purchaseJson) + ","
                + "\"movement\":" + (movementJson == null ? "null" : movementJson)
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
                + "\"action\":\"APPROACH_SHOP\","
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

    private String shopJson(AgentActionContext context, Shop shop) {
        if (shop == null) {
            return "null";
        }
        return "{"
                + "\"shopId\":" + shop.getId() + ","
                + "\"npcId\":" + shop.getNpcId() + ","
                + "\"itemCount\":" + shop.getItems().size() + ","
                + "\"recoveryCandidates\":" + recoveryItemsJson(context, shop)
                + "}";
    }

    private boolean isRecoveryTarget(String target) {
        if (target == null) {
            return false;
        }
        String normalized = target.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("recovery")
                || normalized.equals("potion")
                || normalized.equals("hp")
                || normalized.equals("health")
                || normalized.equals("mp")
                || normalized.equals("mana");
    }

    private boolean hasRecoveryCandidate(Shop shop, String target) {
        return shop.getItems().stream()
                .anyMatch(item -> isMatchingRecoveryItem(item, target));
    }

    private Optional<ShopSelection> selectRecoveryPurchase(Shop shop, String target, int meso) {
        List<ShopItem> items = shop.getItems();
        ShopSelection best = null;
        for (short slot = 0; slot < items.size(); slot++) {
            ShopItem item = items.get(slot);
            if (!isMatchingRecoveryItem(item, target) || item.getPrice() <= 0 || item.getPitch() > 0 || item.getPrice() > meso) {
                continue;
            }
            ShopSelection candidate = new ShopSelection(slot, item);
            if (best == null || item.getPrice() < best.item().getPrice()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private String recoveryItemsJson(AgentActionContext context, Shop shop) {
        Character character = context.managed().character();
        int meso = character == null ? 0 : character.getMeso();
        List<ShopItem> items = shop.getItems().stream()
                .filter(item -> isMatchingRecoveryItem(item, context.intent().argument()))
                .limit(8)
                .toList();
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(shopItemJson(items.get(i), meso));
        }
        return builder.append(']').toString();
    }

    private boolean isMatchingRecoveryItem(ShopItem item, String target) {
        StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
        if (effect == null || !hasRecovery(effect)) {
            return false;
        }
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("hp") || normalized.equals("health")) {
            return effect.getHp() > 0 || effect.getHpRate() > 0.0;
        }
        if (normalized.equals("mp") || normalized.equals("mana")) {
            return effect.getMp() > 0 || effect.getMpRate() > 0.0;
        }
        return true;
    }

    private boolean hasRecovery(StatEffect effect) {
        return effect.getHp() > 0 || effect.getMp() > 0 || effect.getHpRate() > 0.0 || effect.getMpRate() > 0.0;
    }

    private String shopItemJson(ShopItem item, int meso) {
        StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
        String name = ItemInformationProvider.getInstance().getName(item.getItemId());
        return "{"
                + "\"itemId\":" + item.getItemId() + ","
                + "\"name\":\"" + escapeJson(name) + "\","
                + "\"price\":" + item.getPrice() + ","
                + "\"pitch\":" + item.getPitch() + ","
                + "\"buyable\":" + item.getBuyable() + ","
                + "\"affordable\":" + (item.getPrice() > 0 && meso >= item.getPrice()) + ","
                + "\"effect\":{\"hp\":" + effect.getHp()
                + ",\"mp\":" + effect.getMp()
                + ",\"hpRate\":" + effect.getHpRate()
                + ",\"mpRate\":" + effect.getMpRate()
                + "}"
                + "}";
    }

    private String purchaseJson(
            AgentActionContext context,
            ShopSelection purchase,
            int beforeQuantity,
            int afterQuantity,
            int beforeMeso,
            int afterMeso,
            boolean bought
    ) {
        ShopItem item = purchase.item();
        return "{"
                + "\"slot\":" + purchase.slot() + ","
                + "\"itemId\":" + item.getItemId() + ","
                + "\"name\":\"" + escapeJson(ItemInformationProvider.getInstance().getName(item.getItemId())) + "\","
                + "\"quantity\":1,"
                + "\"price\":" + item.getPrice() + ","
                + "\"mesoDelta\":" + (afterMeso - beforeMeso) + ","
                + "\"beforeMeso\":" + beforeMeso + ","
                + "\"afterMeso\":" + afterMeso + ","
                + "\"beforeQuantity\":" + beforeQuantity + ","
                + "\"afterQuantity\":" + afterQuantity + ","
                + "\"bought\":" + bought + ","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId()
                + "}";
    }

    private String itemLabel(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return (name == null || name.isBlank()) ? String.valueOf(itemId) : name + " (" + itemId + ")";
    }

    private String npcJson(AgentPerceptionSnapshot.AgentVisibleObject npc) {
        return "{"
                + "\"objectId\":" + npc.objectId() + ","
                + "\"npcId\":" + nullableNumber(npc.templateId()) + ","
                + "\"name\":\"" + escapeJson(npc.name()) + "\","
                + "\"position\":{\"x\":" + npc.x() + ",\"y\":" + npc.y() + "},"
                + "\"distanceSq\":" + npc.distanceSq()
                + "}";
    }

    private String npcLabel(AgentPerceptionSnapshot.AgentVisibleObject npc) {
        if (npc.name() != null && !npc.name().isBlank()) {
            return npc.name();
        }
        if (npc.templateId() != null) {
            return String.valueOf(npc.templateId());
        }
        return String.valueOf(npc.objectId());
    }

    private String pointLabel(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private String nullableNumber(Number value) {
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

    private record ShopTarget(AgentPerceptionSnapshot.AgentVisibleObject npc, Shop shop) {
    }

    private record ShopSelection(short slot, ShopItem item) {
    }
}
