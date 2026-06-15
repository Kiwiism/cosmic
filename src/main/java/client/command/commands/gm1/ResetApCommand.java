package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.command.Command;

public class ResetApCommand extends Command {
    {
        setDescription("Reset primary stats to 4 and restore legitimate AP from level and job progression.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        int availableAp = player.resetAbilityPoints();

        if (availableAp < 0) {
            player.yellowMessage("AP reset cancelled because the refunded AP would exceed the configured AP limit.");
        } else {
            player.yellowMessage("Primary stats reset. Available AP restored to the legitimate total of "
                    + availableAp + ".");
        }
    }
}
