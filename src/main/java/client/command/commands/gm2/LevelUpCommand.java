package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Stat;
import client.command.Command;
import config.YamlConfig;

public class LevelUpCommand extends Command {
    {
        setDescription("Advance to the next level and apply normal level-up rewards.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (player.getLevel() >= player.getMaxClassLevel()) {
            player.yellowMessage("You are already at the maximum level.");
            return;
        }

        levelTo(player, player.getLevel() + 1);
        player.yellowMessage("Level up complete. You are now level " + player.getLevel() + ".");
    }

    static int levelTo(Character player, int targetLevel) {
        int startLevel = player.getLevel();
        int cappedTargetLevel = Math.max(1, Math.min(targetLevel, player.getMaxClassLevel()));
        player.loseExp(player.getExp(), false, false);

        if (cappedTargetLevel <= player.getLevel()) {
            player.setLevel(cappedTargetLevel);
            player.updateSingleStat(Stat.LEVEL, cappedTargetLevel);
            refreshRates(player);
            return cappedTargetLevel - startLevel;
        }

        while (player.getLevel() < cappedTargetLevel) {
            player.levelUp(false);
        }
        return player.getLevel() - startLevel;
    }

    private static void refreshRates(Character player) {
        player.resetPlayerRates();
        if (YamlConfig.config.server.USE_ADD_RATES_BY_LEVEL) {
            player.setPlayerRates();
        }
        player.setWorldRates();
    }
}
