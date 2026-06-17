package server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses lightweight agent scripts into intents without executing them.
 *
 * Supported MVP commands:
 * IDLE [seconds]
 * WAIT [seconds]
 * SAY [message]
 * ROAM [hint]
 * FOLLOW [character name or id]
 * MOVE [destination]
 * MAP [mapId]
 * PORTAL [portalName]
 * ATTACK [target]
 * GRIND [target]
 * LOOT [target]
 * NPC [npcIdOrName]
 * SHOP [shopIdOrName]
 * TRADE [characterName]
 * PARTY [command]
 * USEITEM [itemIdOrName]
 * EQUIP [itemIdOrName]
 * SKILL [skillIdOrAlias]
 *
 * Convenience syntax:
 * REPEAT 3 SAY hello
 * 3x WAIT 1
 * Inline comments are allowed after commands with " #".
 */
public final class AgentScriptRunner {
    private static final long DEFAULT_IDLE_MILLIS = 30_000L;
    private static final int MAX_REPEAT = 50;

    public List<AgentIntent> parse(String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            return List.of(AgentIntent.idle(DEFAULT_IDLE_MILLIS));
        }

        List<AgentIntent> intents = new ArrayList<>();
        for (String rawLine : scriptBody.split("\\R")) {
            String line = stripInlineComment(rawLine).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            ExpandedLine expandedLine = expandRepeat(line);
            String[] parts = expandedLine.commandLine().split("\\s+", 2);
            String command = parts[0].toUpperCase(Locale.ROOT);
            String argument = parts.length > 1 ? parts[1].strip() : "";
            AgentIntent intent = parseLine(command, argument, expandedLine.commandLine());
            for (int repeat = 0; repeat < expandedLine.repeatCount(); repeat++) {
                intents.add(intent);
            }
        }

        return intents.isEmpty() ? List.of(AgentIntent.idle(DEFAULT_IDLE_MILLIS)) : List.copyOf(intents);
    }

    private String stripInlineComment(String line) {
        int marker = line.indexOf(" #");
        return marker < 0 ? line : line.substring(0, marker);
    }

    private ExpandedLine expandRepeat(String line) {
        String[] parts = line.split("\\s+", 3);
        if (parts.length >= 3 && "REPEAT".equalsIgnoreCase(parts[0])) {
            Integer repeat = repeatCount(parts[1]);
            if (repeat != null) {
                return new ExpandedLine(parts[2].strip(), repeat);
            }
        }

        if (parts.length >= 2 && parts[0].toLowerCase(Locale.ROOT).endsWith("x")) {
            Integer repeat = repeatCount(parts[0].substring(0, parts[0].length() - 1));
            if (repeat != null) {
                return new ExpandedLine((parts.length == 2 ? parts[1] : parts[1] + " " + parts[2]).strip(), repeat);
            }
        }

        return new ExpandedLine(line, 1);
    }

    private Integer repeatCount(String value) {
        try {
            int repeat = Integer.parseInt(value.trim());
            return repeat < 1 ? null : Math.min(repeat, MAX_REPEAT);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private AgentIntent parseLine(String command, String argument, String originalLine) {
        return switch (command) {
            case "IDLE" -> AgentIntent.idle(secondsToMillis(argument, DEFAULT_IDLE_MILLIS));
            case "WAIT" -> AgentIntent.waitFor(secondsToMillis(argument, DEFAULT_IDLE_MILLIS));
            case "SAY" -> AgentIntent.say(argument);
            case "ROAM" -> AgentIntent.roam(argument);
            case "FOLLOW", "FOLLOW_CHARACTER", "COMPANION" -> AgentIntent.followCharacter(argument);
            case "MOVE" -> AgentIntent.move(argument);
            case "MAP", "MOVEMAP", "MOVE_TO_MAP" -> AgentIntent.moveToMap(argument);
            case "PORTAL", "USEPORTAL", "USE_PORTAL" -> AgentIntent.usePortal(argument);
            case "ATTACK", "KILL" -> AgentIntent.attack(argument);
            case "GRIND", "TRAIN" -> AgentIntent.grind(argument);
            case "LOOT", "PICKUP" -> AgentIntent.loot(argument);
            case "NPC", "TALK" -> AgentIntent.npc(argument);
            case "SHOP", "MERCHANT" -> AgentIntent.shop(argument);
            case "TRADE" -> AgentIntent.trade(argument);
            case "PARTY" -> AgentIntent.party(argument);
            case "USEITEM", "USE_ITEM", "CONSUME" -> AgentIntent.useItem(argument);
            case "EQUIP" -> AgentIntent.equip(argument);
            case "SKILL", "CAST", "USESKILL", "USE_SKILL" -> AgentIntent.skill(argument);
            default -> AgentIntent.unknown(originalLine);
        };
    }

    private long secondsToMillis(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1L, Long.parseLong(value.trim())) * 1000L;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record ExpandedLine(String commandLine, int repeatCount) {
    }
}
