/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command;

import client.Client;
import server.configuration.CommandPolicyOverrides;
import client.command.commands.gm0.ChangeLanguageCommand;
import client.command.commands.gm0.BuffsCommand;
import client.command.commands.gm0.CooldownsCommand;
import client.command.commands.gm0.DisposeCommand;
import client.command.commands.gm0.DropLimitCommand;
import client.command.commands.gm0.EnableAuthCommand;
import client.command.commands.gm0.EquipLvCommand;
import client.command.commands.gm0.EventInfoCommand;
import client.command.commands.gm0.GachaCommand;
import client.command.commands.gm0.GmCommand;
import client.command.commands.gm0.HelpCommand;
import client.command.commands.gm0.JoinEventCommand;
import client.command.commands.gm0.LeaveEventCommand;
import client.command.commands.gm0.MapInfoCommand;
import client.command.commands.gm0.MapOwnerClaimCommand;
import client.command.commands.gm0.OnlineCommand;
import client.command.commands.gm0.PartyInfoCommand;
import client.command.commands.gm0.QuestStatusCommand;
import client.command.commands.gm0.RanksCommand;
import client.command.commands.gm0.RatesCommand;
import client.command.commands.gm0.ReadPointsCommand;
import client.command.commands.gm0.ReportBugCommand;
import client.command.commands.gm0.ShowRatesCommand;
import client.command.commands.gm0.StaffCommand;
import client.command.commands.gm0.StatDexCommand;
import client.command.commands.gm0.StatIntCommand;
import client.command.commands.gm0.StatLukCommand;
import client.command.commands.gm0.StatStrCommand;
import client.command.commands.gm0.TimeCommand;
import client.command.commands.gm0.ToggleExpCommand;
import client.command.commands.gm0.UptimeCommand;
import client.command.commands.gm1.BossHpCommand;
import client.command.commands.gm1.BuffMeCommand;
import client.command.commands.gm1.GotoCommand;
import client.command.commands.gm1.MobHpCommand;
import client.command.commands.gm1.ResetApCommand;
import client.command.commands.gm1.ResetSpCommand;
import client.command.commands.gm1.WhatDropsFromCommand;
import client.command.commands.gm1.WhoDropsCommand;
import client.command.commands.gm2.BombCommand;
import client.command.commands.gm2.BuffCommand;
import client.command.commands.gm2.BuffMapCommand;
import client.command.commands.gm2.ClearDropsCommand;
import client.command.commands.gm2.ClearBuffsCommand;
import client.command.commands.gm2.ClearCooldownsCommand;
import client.command.commands.gm2.ClearSavedLocationsCommand;
import client.command.commands.gm2.ClearSlotCommand;
import client.command.commands.gm2.CharInfoCommand;
import client.command.commands.gm2.DcCommand;
import client.command.commands.gm2.EmpowerMeCommand;
import client.command.commands.gm2.GachaListCommand;
import client.command.commands.gm2.GmShopCommand;
import client.command.commands.gm2.HealCommand;
import client.command.commands.gm2.HideCommand;
import client.command.commands.gm2.IdCommand;
import client.command.commands.gm2.ItemCommand;
import client.command.commands.gm2.ItemDropCommand;
import client.command.commands.gm2.InventoryInfoCommand;
import client.command.commands.gm2.JailCommand;
import client.command.commands.gm2.JobCommand;
import client.command.commands.gm2.LevelCommand;
import client.command.commands.gm2.LevelUpCommand;
import client.command.commands.gm2.LootCommand;
import client.command.commands.gm2.MaxSkillCommand;
import client.command.commands.gm2.MaxStatCommand;
import client.command.commands.gm2.MapDebugCommand;
import client.command.commands.gm2.MobSkillCommand;
import client.command.commands.gm2.ReachCommand;
import client.command.commands.gm2.RechargeCommand;
import client.command.commands.gm2.ResetHpCommand;
import client.command.commands.gm2.ResetHpMpCommand;
import client.command.commands.gm2.ResetMpCommand;
import client.command.commands.gm2.ResetSkillCommand;
import client.command.commands.gm2.SearchCommand;
import client.command.commands.gm2.SetSlotCommand;
import client.command.commands.gm2.SetStatCommand;
import client.command.commands.gm2.SummonCommand;
import client.command.commands.gm2.TestShopCommand;
import client.command.commands.gm2.UnBugCommand;
import client.command.commands.gm2.UnHideCommand;
import client.command.commands.gm2.UnJailCommand;
import client.command.commands.gm2.WarpAreaCommand;
import client.command.commands.gm2.WarpCommand;
import client.command.commands.gm2.WarpMapCommand;
import client.command.commands.gm2.WhereaMiCommand;
import client.command.commands.gm3.BanCommand;
import client.command.commands.gm3.ChatCommand;
import client.command.commands.gm3.CheckDmgCommand;
import client.command.commands.gm3.ClearPlayerDropsCommand;
import client.command.commands.gm3.ClearPlayerInventoryCommand;
import client.command.commands.gm3.ClosePortalCommand;
import client.command.commands.gm3.DebuffCommand;
import client.command.commands.gm3.EndEventCommand;
import client.command.commands.gm3.ExpedsCommand;
import client.command.commands.gm3.FaceCommand;
import client.command.commands.gm3.FameCommand;
import client.command.commands.gm3.FlyCommand;
import client.command.commands.gm3.GiveMesosCommand;
import client.command.commands.gm3.GiveNxCommand;
import client.command.commands.gm3.GiveRpCommand;
import client.command.commands.gm3.GiveVpCommand;
import client.command.commands.gm3.HairCommand;
import client.command.commands.gm3.HealMapCommand;
import client.command.commands.gm3.HealPersonCommand;
import client.command.commands.gm3.HpMpCommand;
import client.command.commands.gm3.HurtCommand;
import client.command.commands.gm3.IgnoreCommand;
import client.command.commands.gm3.IgnoredCommand;
import client.command.commands.gm3.InMapCommand;
import client.command.commands.gm3.KillAllCommand;
import client.command.commands.gm3.KillCommand;
import client.command.commands.gm3.KillMapCommand;
import client.command.commands.gm3.MaxEnergyCommand;
import client.command.commands.gm3.MaxHpMpCommand;
import client.command.commands.gm3.MonitorCommand;
import client.command.commands.gm3.MonitorsCommand;
import client.command.commands.gm3.MusicCommand;
import client.command.commands.gm3.MuteMapCommand;
import client.command.commands.gm3.NightCommand;
import client.command.commands.gm3.NoticeCommand;
import client.command.commands.gm3.NpcCommand;
import client.command.commands.gm3.OnlineTwoCommand;
import client.command.commands.gm3.OpenPortalCommand;
import client.command.commands.gm3.PeCommand;
import client.command.commands.gm3.PlayerCompleteQuestCommand;
import client.command.commands.gm3.PlayerInfoCommand;
import client.command.commands.gm3.PlayerResetQuestCommand;
import client.command.commands.gm3.PlayerStartQuestCommand;
import client.command.commands.gm3.PosCommand;
import client.command.commands.gm3.QuestCompleteCommand;
import client.command.commands.gm3.QuestResetCommand;
import client.command.commands.gm3.QuestStartCommand;
import client.command.commands.gm3.ReloadDropsCommand;
import client.command.commands.gm3.ReloadEventsCommand;
import client.command.commands.gm3.ReloadMapCommand;
import client.command.commands.gm3.ReloadPortalsCommand;
import client.command.commands.gm3.ReloadShopsCommand;
import client.command.commands.gm3.RipCommand;
import client.command.commands.gm3.SeedCommand;
import client.command.commands.gm3.SpawnCommand;
import client.command.commands.gm3.StartEventCommand;
import client.command.commands.gm3.StartMapEventCommand;
import client.command.commands.gm3.StaffNoteCommand;
import client.command.commands.gm3.StopMapEventCommand;
import client.command.commands.gm3.TimerAllCommand;
import client.command.commands.gm3.TimerCommand;
import client.command.commands.gm3.TimerMapCommand;
import client.command.commands.gm3.ToggleCouponCommand;
import client.command.commands.gm3.UnBanCommand;
import client.command.commands.gm4.BossDropRateCommand;
import client.command.commands.gm4.CakeCommand;
import client.command.commands.gm4.DropRateCommand;
import client.command.commands.gm4.ExpRateCommand;
import client.command.commands.gm4.FishingRateCommand;
import client.command.commands.gm4.ForceVacCommand;
import client.command.commands.gm4.HorntailCommand;
import client.command.commands.gm4.ItemVacCommand;
import client.command.commands.gm4.MapObjectsCommand;
import client.command.commands.gm4.MesoRateCommand;
import client.command.commands.gm4.PapCommand;
import client.command.commands.gm4.PianusCommand;
import client.command.commands.gm4.PinkbeanCommand;
import client.command.commands.gm4.PlayerNpcCommand;
import client.command.commands.gm4.PlayerNpcRemoveCommand;
import client.command.commands.gm4.PmobCommand;
import client.command.commands.gm4.PmobRemoveCommand;
import client.command.commands.gm4.PnpcCommand;
import client.command.commands.gm4.PnpcRemoveCommand;
import client.command.commands.gm4.ProItemCommand;
import client.command.commands.gm4.QuestRateCommand;
import client.command.commands.gm4.ResetReactorsCommand;
import client.command.commands.gm4.ServerMessageCommand;
import client.command.commands.gm4.SetEqStatCommand;
import client.command.commands.gm4.TravelRateCommand;
import client.command.commands.gm4.ZakumCommand;
import client.command.commands.gm5.DebugCommand;
import client.command.commands.gm5.ChannelHealthCommand;
import client.command.commands.gm5.IpListCommand;
import client.command.commands.gm5.SetCommand;
import client.command.commands.gm5.ServerHealthCommand;
import client.command.commands.gm5.ShowMoveLifeCommand;
import client.command.commands.gm5.ShowPacketsCommand;
import client.command.commands.gm5.ShowSessionsCommand;
import client.command.commands.gm5.TimerStatsCommand;
import client.command.commands.gm5.WorldHealthCommand;
import client.command.commands.gm6.ClearQuestCacheCommand;
import client.command.commands.gm6.ClearQuestCommand;
import client.command.commands.gm6.DCAllCommand;
import client.command.commands.gm6.DevtestCommand;
import client.command.commands.gm6.EraseAllPNpcsCommand;
import client.command.commands.gm6.GetAccCommand;
import client.command.commands.gm6.MapPlayersCommand;
import client.command.commands.gm6.SaveAllCommand;
import client.command.commands.gm6.ServerAddChannelCommand;
import client.command.commands.gm6.ServerAddWorldCommand;
import client.command.commands.gm6.ServerRemoveChannelCommand;
import client.command.commands.gm6.ServerRemoveWorldCommand;
import client.command.commands.gm6.SetGmLevelCommand;
import client.command.commands.gm6.ShutdownCommand;
import client.command.commands.gm6.SpawnAllPNpcsCommand;
import client.command.commands.gm6.SupplyRateCouponCommand;
import client.command.commands.gm6.WarpWorldCommand;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CommandsExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommandsExecutor.class);
    private static final CommandsExecutor instance = new CommandsExecutor();
    private static final char USER_HEADING = '@';
    private static final char GM_HEADING = '!';

    private final HashMap<String, Command> registeredCommands = new HashMap<>();
    private final List<Pair<List<String>, List<String>>> commandsNameDesc = new ArrayList<>();
    private Pair<List<String>, List<String>> levelCommandsCursor;

    public static CommandsExecutor getInstance() {
        return instance;
    }

    public static boolean isCommand(Client client, String content) {
        char heading = content.charAt(0);
        if (client.getPlayer().isGM()) {
            return heading == USER_HEADING || heading == GM_HEADING;
        }
        return heading == USER_HEADING;
    }

    private CommandsExecutor() {
        registerLv0Commands();
        registerLv1Commands();
        registerLv2Commands();
        registerLv3Commands();
        registerLv4Commands();
        registerLv5Commands();
        registerLv6Commands();
    }

    public List<Pair<List<String>, List<String>>> getGmCommands() {
        return commandsNameDesc;
    }

    /**
     * Exposes the configured rank for tests and command-audit tooling.
     */
    public int getCommandRank(String commandName) {
        Command command = registeredCommands.get(commandName.toLowerCase());
        return command == null ? -1 : command.getRank();
    }

    /**
     * Exposes registry totals for tests and command-audit tooling.
     */
    public int getRegisteredCommandCount() {
        return registeredCommands.size();
    }

    public long getRegisteredCommandImplementationCount() {
        return registeredCommands.values().stream()
                .map(Command::getClass)
                .distinct()
                .count();
    }

    public void handle(Client client, String message) {
        if (client.tryacquireClient()) {
            try {
                handleInternal(client, message);
            } finally {
                client.releaseClient();
            }
        } else {
            client.getPlayer().dropMessage(5, "Try again in a while... Latest commands are currently being processed.");
        }
    }

    private void handleInternal(Client client, String message) {
        if (client.getPlayer().getMapId() == MapId.JAIL) {
            client.getPlayer().yellowMessage("You do not have permission to use commands while in jail.");
            return;
        }
        final String splitRegex = "[ ]";
        String[] splitedMessage = message.substring(1).split(splitRegex, 2);
        if (splitedMessage.length < 2) {
            splitedMessage = new String[]{splitedMessage[0], ""};
        }

        client.getPlayer().setLastCommandMessage(splitedMessage[1]);    // thanks Tochi & Nulliphite for noticing string messages being marshalled lowercase
        final String commandName = splitedMessage[0].toLowerCase();
        final String[] lowercaseParams = splitedMessage[1].toLowerCase().split(splitRegex);

        final Command command = registeredCommands.get(commandName);
        if (command == null) {
            client.getPlayer().yellowMessage("Command '" + commandName + "' is not available. See @commands for a list of available commands.");
            return;
        }
        if (client.getPlayer().gmLevel() < command.getRank()) {
            client.getPlayer().yellowMessage("You do not have permission to use this command.");
            return;
        }
        String[] params;
        if (lowercaseParams.length > 0 && !lowercaseParams[0].isEmpty()) {
            params = Arrays.copyOfRange(lowercaseParams, 0, lowercaseParams.length);
        } else {
            params = new String[]{};
        }

        command.execute(client, params);
        log.info("Chr {} used command {}", client.getPlayer().getName(), command.getClass().getSimpleName());
    }

    private void addCommandInfo(String name, Class<? extends Command> commandClass) {
        try {
            levelCommandsCursor.getRight().add(commandClass.getDeclaredConstructor().newInstance().getDescription());
            levelCommandsCursor.getLeft().add(name);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addCommand(String syntax, int rank, Class<? extends Command> commandClass) {
        if (!CommandPolicyOverrides.enabled(syntax)) {
            return;
        }
        rank = CommandPolicyOverrides.requiredLevel(syntax, rank);
        if (registeredCommands.containsKey(syntax.toLowerCase())) {
            log.warn("Error on register command with name: {}. Already exists.", syntax);
            return;
        }

        String commandName = syntax.toLowerCase();
        addCommandInfo(commandName, commandClass);

        try {
            Command commandInstance = commandClass.getDeclaredConstructor().newInstance();     // thanks Halcyon for noticing commands getting reinstanced every call
            commandInstance.setRank(rank);

            registeredCommands.put(commandName, commandInstance);
        } catch (Exception e) {
            log.warn("Failed to create command instance", e);
        }
    }

    private void registerLv0Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("commands", 0, HelpCommand.class); // List commands available to the character.
        addCommand("time", 0, TimeCommand.class); // Show current server time.
        addCommand("uptime", 0, UptimeCommand.class); // Show how long the server has been running.
        addCommand("credits", 0, StaffCommand.class); // Show project contributor credits.
        addCommand("rates", 0, RatesCommand.class); // Show effective character rates.
        addCommand("showrates", 0, ShowRatesCommand.class); // Show detailed world, coupon, and character rates.
        addCommand("online", 0, OnlineCommand.class); // Show online names without exposing player locations.
        addCommand("ranks", 0, RanksCommand.class); // Show character ranking information.
        addCommand("points", 0, ReadPointsCommand.class); // Show the character's configured point currencies.
        addCommand("equiplv", 0, EquipLvCommand.class); // Show equipped-item level information.
        addCommand("reportbug", 0, ReportBugCommand.class); // Submit a player bug report.
        addCommand("contactgm", 0, GmCommand.class); // Send a message to online staff.
        addCommand("language", 0, ChangeLanguageCommand.class); // Change supported script language.
        addCommand("enableauth", 0, EnableAuthCommand.class); // Reset PIN/PIC authentication bypass state.
        addCommand("joinevent", 0, JoinEventCommand.class); // Join the currently available event.
        addCommand("leaveevent", 0, LeaveEventCommand.class); // Leave the current event.
        addCommand("toggleexp", 0, ToggleExpCommand.class); // Toggle personal EXP gain.
        addCommand("mylawn", 0, MapOwnerClaimCommand.class); // Claim a map under the configured ownership system.
        addCommand("dispose", 0, DisposeCommand.class); // Recover from stuck NPC or quest conversations.
        addCommand("unbug", 0, UnBugCommand.class); // Re-enable this client's actions.
        addCommand("clearsavelocs", 0, ClearSavedLocationsCommand.class); // Clear the character's saved return locations.
        addCommand("mapinfo", 0, MapInfoCommand.class); // Show basic current-map information.
        addCommand("queststatus", 0, QuestStatusCommand.class); // Show the character's state for one quest.
        addCommand("buffs", 0, BuffsCommand.class); // List active buff source IDs.
        addCommand("cooldowns", 0, CooldownsCommand.class); // List active skill cooldowns.
        addCommand("partyinfo", 0, PartyInfoCommand.class); // Show the character's party membership.
        addCommand("eventinfo", 0, EventInfoCommand.class); // Show the character's attached event instance.

        commandsNameDesc.add(levelCommandsCursor);
    }


    private void registerLv1Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("gacha", 1, GachaCommand.class); // Show gachapon reward information.
        addCommand("gachalist", 1, GachaListCommand.class); // Show the expanded gachapon reward listing.
        addCommand("bosshp", 1, BossHpCommand.class); // Show boss HP on the current map.
        addCommand("mobhp", 1, MobHpCommand.class); // Show monster HP on the current map.
        addCommand("mobdrops", 1, WhatDropsFromCommand.class); // Find items dropped by a monster.
        addCommand("itemdrops", 1, WhoDropsCommand.class); // Find monsters that drop an item.
        addCommand("str", 1, StatStrCommand.class); // Assign owned AP into STR.
        addCommand("dex", 1, StatDexCommand.class); // Assign owned AP into DEX.
        addCommand("int", 1, StatIntCommand.class); // Assign owned AP into INT.
        addCommand("luk", 1, StatLukCommand.class); // Assign owned AP into LUK.
        addCommand("gototown", 1, GotoCommand.class); // Warp to the built-in safe town allowlist.
        addCommand("resetap", 1, ResetApCommand.class); // Reset primary stats to 4 and refund exactly the removed AP.
        addCommand("resetsp", 1, ResetSpCommand.class); // Reset current job-tree skills and refund SP by skill book.

        commandsNameDesc.add(levelCommandsCursor);
    }


    private void registerLv2Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("search", 2, SearchCommand.class); // Search String.wz names for testing IDs.
        addCommand("idsearch", 2, IdCommand.class); // Search the in-game handbook for IDs.
        addCommand("droplimit", 2, DropLimitCommand.class); // Show current map drop count and configured limit.
        addCommand("whereami", 2, WhereaMiCommand.class); // List technical map object IDs and object IDs.
        addCommand("position", 2, PosCommand.class); // Show exact coordinates and foothold ID.
        addCommand("checkdmg", 2, CheckDmgCommand.class); // Inspect only the invoking character's damage.
        addCommand("charinfo", 2, CharInfoCommand.class); // Show a self-only character testing summary.
        addCommand("inventoryinfo", 2, InventoryInfoCommand.class); // Show self inventory slot usage.
        addCommand("mapdebug", 2, MapDebugCommand.class); // Show current-map object counts.
        addCommand("level", 2, LevelCommand.class); // Set the invoking character's level.
        addCommand("levelup", 2, LevelUpCommand.class); // Advance one level using the normal level-up reward pipeline.
        addCommand("job", 2, JobCommand.class); // Change only the invoking character's job.
        addCommand("setstat", 2, SetStatCommand.class); // Set all primary stats on self.
        addCommand("maxstat", 2, MaxStatCommand.class); // Maximize self primary stats.
        addCommand("maxskill", 2, MaxSkillCommand.class); // Maximize only self skills in the current job tree.
        addCommand("resetskill", 2, ResetSkillCommand.class); // Reset self skills to zero.
        addCommand("heal", 2, HealCommand.class); // Fully restore self HP and MP.
        addCommand("resethp", 2, ResetHpCommand.class); // Normalize self base HP from average level and advancement growth.
        addCommand("resetmp", 2, ResetMpCommand.class); // Normalize self base MP from average level and advancement growth.
        addCommand("resethpmp", 2, ResetHpMpCommand.class); // Normalize both self base HP and MP from average progression.
        addCommand("sethpmp", 2, HpMpCommand.class); // Set only the invoking character's HP/MP.
        addCommand("setmaxhpmp", 2, MaxHpMpCommand.class); // Set only the invoking character's maximum HP/MP.
        addCommand("setslot", 2, SetSlotCommand.class); // Set self inventory slot capacity.
        addCommand("clearinventory", 2, ClearSlotCommand.class); // Clear an inventory tab on self.
        addCommand("recharge", 2, RechargeCommand.class); // Refill self rechargeable USE items.
        addCommand("loot", 2, LootCommand.class); // Loot only drops directly owned by the invoking character.
        addCommand("clearowndrops", 2, ClearDropsCommand.class); // Remove drops owned by the invoking character.
        addCommand("warp", 2, WarpCommand.class); // Warp self while retaining restricted-map checks.
        addCommand("buff", 2, BuffCommand.class); // Apply a selected current-job skill buff only to self.
        addCommand("buffme", 2, BuffMeCommand.class); // Apply the predefined tester buff set to self.
        addCommand("empowerme", 2, EmpowerMeCommand.class); // Apply the extended tester buff set to self.
        addCommand("clearbuffs", 2, ClearBuffsCommand.class); // Clear all buffs on self.
        addCommand("clearcooldowns", 2, ClearCooldownsCommand.class); // Clear all skill cooldowns on self.
        addCommand("startquest", 2, QuestStartCommand.class); // Force-start a quest on self.
        addCommand("completequest", 2, QuestCompleteCommand.class); // Force-complete an active quest on self.
        addCommand("resetquest", 2, QuestResetCommand.class); // Reset a quest on self.
        addCommand("testshop", 2, TestShopCommand.class); // Placeholder until a DB shop and configured allowlist exist.

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv3Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("inmap", 3, InMapCommand.class); // List players in the current map.
        addCommand("onlineinfo", 3, OnlineTwoCommand.class); // Show detailed online population.
        addCommand("expeds", 3, ExpedsCommand.class); // Show ongoing boss expeditions.
        addCommand("hide", 3, HideCommand.class); // Hide staff from non-staff players unless already hidden.
        addCommand("unhide", 3, UnHideCommand.class); // Make hidden staff visible unless already visible.
        addCommand("fly", 3, FlyCommand.class); // Toggle staff fly functionality.
        addCommand("warpto", 3, ReachCommand.class); // Warp staff to another player.
        addCommand("warphere", 3, SummonCommand.class); // Warp another player to staff.
        addCommand("warpmap", 3, WarpMapCommand.class); // Warp every character in the current map.
        addCommand("warparea", 3, WarpAreaCommand.class); // Warp nearby characters.
        addCommand("jail", 3, JailCommand.class); // Move a player to jail.
        addCommand("unjail", 3, UnJailCommand.class); // Release a player from jail.
        addCommand("disconnect", 3, DcCommand.class); // Disconnect one player.
        addCommand("ban", 3, BanCommand.class); // Ban a player.
        addCommand("unban", 3, UnBanCommand.class); // Unban an account.
        addCommand("mutemap", 3, MuteMapCommand.class); // Toggle chat mute for the current map.
        addCommand("healperson", 3, HealPersonCommand.class); // Restore one player's HP/MP.
        addCommand("healmap", 3, HealMapCommand.class); // Restore all players on the current map.
        addCommand("face", 3, FaceCommand.class); // Change a player's face.
        addCommand("hair", 3, HairCommand.class); // Change a player's hair.
        addCommand("fame", 3, FameCommand.class); // Set a player's fame.
        addCommand("gmshop", 3, GmShopCommand.class); // Open the existing unrestricted GM shop.
        addCommand("playerinfo", 3, PlayerInfoCommand.class); // Show support-safe information about one online player.
        addCommand("clearplayerinventory", 3, ClearPlayerInventoryCommand.class); // Clear one explicit inventory tab for a player.
        addCommand("clearplayerdrops", 3, ClearPlayerDropsCommand.class); // Remove drops owned by one player.
        addCommand("playerstartquest", 3, PlayerStartQuestCommand.class); // Force-start a quest for another player.
        addCommand("playercompletequest", 3, PlayerCompleteQuestCommand.class); // Force-complete a quest for another player.
        addCommand("playerresetquest", 3, PlayerResetQuestCommand.class); // Reset a quest for another player.
        addCommand("startevent", 3, StartEventCommand.class); // Start entry for an event.
        addCommand("endevent", 3, EndEventCommand.class); // Close entry for an event.
        addCommand("startmapevent", 3, StartMapEventCommand.class); // Start a classic map event.
        addCommand("stopmapevent", 3, StopMapEventCommand.class); // Stop a classic map event.
        addCommand("openportal", 3, OpenPortalCommand.class); // Open a portal in the current map.
        addCommand("closeportal", 3, ClosePortalCommand.class); // Close a portal in the current map.
        addCommand("timer", 3, TimerCommand.class); // Set a timer for one player.
        addCommand("timermap", 3, TimerMapCommand.class); // Set a timer for the current map.
        addCommand("timerall", 3, TimerAllCommand.class); // Set a server-wide timer.
        addCommand("reloadevents", 3, ReloadEventsCommand.class); // Reload event scripts.
        addCommand("reloaddrops", 3, ReloadDropsCommand.class); // Reload monster drop data.
        addCommand("reloadportals", 3, ReloadPortalsCommand.class); // Reload portal scripts.
        addCommand("reloadmap", 3, ReloadMapCommand.class); // Reload the current map.
        addCommand("reloadshops", 3, ReloadShopsCommand.class); // Reload NPC and popup shops.
        addCommand("notice", 3, NoticeCommand.class); // Broadcast a world notice.
        addCommand("togglewhitechat", 3, ChatCommand.class); // Toggle white staff chat.
        addCommand("night", 3, NightCommand.class); // Toggle the current map's night effect.
        addCommand("music", 3, MusicCommand.class); // Change current map music.
        addCommand("togglecoupon", 3, ToggleCouponCommand.class); // Toggle a coupon's active state.
        addCommand("staffnote", 3, StaffNoteCommand.class); // Placeholder until moderation-note persistence exists.

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv4Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("giveitem", 4, ItemCommand.class); // Create an item in the moderator's inventory.
        addCommand("dropitem", 4, ItemDropCommand.class); // Create an item drop on the map.
        addCommand("proitem", 4, ProItemCommand.class); // Create equipment with custom enhanced stats.
        addCommand("seteqstat", 4, SetEqStatCommand.class); // Rewrite stats on inventory equipment.
        addCommand("itemvac", 4, ItemVacCommand.class); // Loot map drops to the moderator.
        addCommand("forcevac", 4, ForceVacCommand.class); // Force-loot all drops on the map.
        addCommand("spawnmob", 4, SpawnCommand.class); // Spawn monsters at the moderator's position.
        addCommand("killall", 4, KillAllCommand.class); // Kill all monsters in the current map.
        addCommand("mobskill", 4, MobSkillCommand.class); // Apply a monster skill to map monsters.
        addCommand("zakum", 4, ZakumCommand.class); // Spawn Zakum.
        addCommand("horntail", 4, HorntailCommand.class); // Spawn Horntail.
        addCommand("pinkbean", 4, PinkbeanCommand.class); // Spawn Pink Bean.
        addCommand("pap", 4, PapCommand.class); // Spawn Papulatus.
        addCommand("pianus", 4, PianusCommand.class); // Spawn Pianus.
        addCommand("cake", 4, CakeCommand.class); // Spawn the Cake boss.
        addCommand("npc", 4, NpcCommand.class); // Spawn a temporary NPC.
        addCommand("playernpc", 4, PlayerNpcCommand.class); // Spawn an online character as a player NPC.
        addCommand("playernpcremove", 4, PlayerNpcRemoveCommand.class); // Remove a player NPC.
        addCommand("spawnpersistentnpc", 4, PnpcCommand.class); // Create a persistent NPC.
        addCommand("pnpcremove", 4, PnpcRemoveCommand.class); // Remove a persistent NPC.
        addCommand("spawnpersistentmob", 4, PmobCommand.class); // Create a persistent monster.
        addCommand("pmobremove", 4, PmobRemoveCommand.class); // Remove persistent monsters of a type.
        addCommand("givenx", 4, GiveNxCommand.class); // Give NX or Maple Points to a player.
        addCommand("givems", 4, GiveMesosCommand.class); // Give mesos to a player.
        addCommand("givevp", 4, GiveVpCommand.class); // Give vote points to a player.
        addCommand("giverp", 4, GiveRpCommand.class); // Give reward points to a player.
        addCommand("buffmap", 4, BuffMapCommand.class); // Apply GM buffs to the current map.
        addCommand("seed", 4, SeedCommand.class); // Drop Henesys PQ seeds.
        addCommand("maxenergy", 4, MaxEnergyCommand.class); // Maximize dojo energy.
        addCommand("kill", 4, KillCommand.class); // Kill one player.
        addCommand("killmap", 4, KillMapCommand.class); // Kill every player on the current map.
        addCommand("hurt", 4, HurtCommand.class); // Reduce a player's HP to near death.
        addCommand("bomb", 4, BombCommand.class); // Damage a player with the bomb action.
        addCommand("debuff", 4, DebuffCommand.class); // Debuff nearby players.
        addCommand("rip", 4, RipCommand.class); // Broadcast a RIP notice.
        addCommand("mapobjects", 4, MapObjectsCommand.class); // List all current-map object types and object IDs.
        addCommand("resetreactors", 4, ResetReactorsCommand.class); // Reset all reactors on the current map.

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv5Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("exprate", 5, ExpRateCommand.class); // Change world EXP rate.
        addCommand("mesorate", 5, MesoRateCommand.class); // Change world meso rate.
        addCommand("droprate", 5, DropRateCommand.class); // Change world drop rate.
        addCommand("bossdroprate", 5, BossDropRateCommand.class); // Change world boss drop rate.
        addCommand("questrate", 5, QuestRateCommand.class); // Change world quest rate.
        addCommand("travelrate", 5, TravelRateCommand.class); // Change world travel rate.
        addCommand("fishingrate", 5, FishingRateCommand.class); // Change world fishing rate.
        addCommand("debug", 5, DebugCommand.class); // Show the built-in debug message.
        addCommand("setdebugvalue", 5, SetCommand.class); // Store an internal testing value.
        addCommand("showpackets", 5, ShowPacketsCommand.class); // Toggle received-packet console logging.
        addCommand("showmovelife", 5, ShowMoveLifeCommand.class); // Toggle movement-life console logging.
        addCommand("showsessions", 5, ShowSessionsCommand.class); // Show detailed active session traces.
        addCommand("iplist", 5, IpListCommand.class); // Show online player IP addresses.
        addCommand("monitor", 5, MonitorCommand.class); // Toggle packet monitoring for one player.
        addCommand("monitors", 5, MonitorsCommand.class); // List packet-monitored players.
        addCommand("ignore", 5, IgnoreCommand.class); // Toggle a player in autoban alert exclusions.
        addCommand("ignored", 5, IgnoredCommand.class); // List autoban alert exclusions.
        addCommand("getaccount", 5, GetAccCommand.class); // Show an online player's account name.
        addCommand("mapplayers", 5, MapPlayersCommand.class); // Show map players and HP values.
        addCommand("warpworld", 5, WarpWorldCommand.class); // Move the administrator to another world.
        addCommand("supplyratecoupon", 5, SupplyRateCouponCommand.class); // Toggle Cash Shop coupon availability.
        addCommand("servermessage", 5, ServerMessageCommand.class); // Set or clear the scrolling world marquee.
        addCommand("serverhealth", 5, ServerHealthCommand.class); // Show JVM memory and world population health.
        addCommand("timerstats", 5, TimerStatsCommand.class); // Show scheduled executor statistics.
        addCommand("worldhealth", 5, WorldHealthCommand.class); // Show world population, channels, and rates.
        addCommand("channelhealth", 5, ChannelHealthCommand.class); // Show current-channel population and merchant health.

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv6Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("setgmlevel", 6, SetGmLevelCommand.class); // Change a character's access level.
        addCommand("saveall", 6, SaveAllCommand.class); // Save every online character.
        addCommand("disconnectall", 6, DCAllCommand.class); // Disconnect all non-staff players.
        addCommand("shutdown", 6, ShutdownCommand.class); // Shut down the server process.
        addCommand("addchannel", 6, ServerAddChannelCommand.class); // Add a channel to a world.
        addCommand("removechannel", 6, ServerRemoveChannelCommand.class); // Remove a channel from a world.
        addCommand("addworld", 6, ServerAddWorldCommand.class); // Add a world.
        addCommand("removeworld", 6, ServerRemoveWorldCommand.class); // Remove a world.
        addCommand("clearquestcache", 6, ClearQuestCacheCommand.class); // Clear all cached quest definitions.
        addCommand("clearquest", 6, ClearQuestCommand.class); // Clear one cached quest definition.
        addCommand("spawnallpnpcs", 6, SpawnAllPNpcsCommand.class); // Spawn player NPCs for all existing players.
        addCommand("eraseallpnpcs", 6, EraseAllPNpcsCommand.class); // Remove all player NPCs.
        addCommand("devtest", 6, DevtestCommand.class); // Run the developer test script.
        addCommand("processpacket", 6, PeCommand.class); // Process synthesized client packet data.

        commandsNameDesc.add(levelCommandsCursor);
    }

}
