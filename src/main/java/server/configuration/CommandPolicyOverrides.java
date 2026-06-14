package server.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Startup-only command visibility and access-level policies from Server CMS.
 * An unavailable CMS database leaves every source registration unchanged.
 */
public final class CommandPolicyOverrides {
    private static final Logger log = LoggerFactory.getLogger(CommandPolicyOverrides.class);
    private static volatile Map<String, Policy> policies = Map.of();

    private CommandPolicyOverrides() {
    }

    public static void load() {
        Map<String, Policy> loaded = new HashMap<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT command_name,enabled,required_level FROM cosmic_server_cms.command_overrides");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                loaded.put(result.getString("command_name").toLowerCase(),
                        new Policy(result.getBoolean("enabled"), result.getInt("required_level")));
            }
            policies = Map.copyOf(loaded);
            log.info("Loaded {} Server CMS command policies", policies.size());
        } catch (Exception unavailable) {
            policies = Map.of();
            log.info("Server CMS command policies unavailable; using source registrations ({})",
                    unavailable.getClass().getSimpleName());
        }
    }

    public static boolean enabled(String commandName) {
        Policy policy = policies.get(commandName.toLowerCase());
        return policy == null || policy.enabled();
    }

    public static int requiredLevel(String commandName, int sourceLevel) {
        Policy policy = policies.get(commandName.toLowerCase());
        return policy == null ? sourceLevel : policy.requiredLevel();
    }

    private record Policy(boolean enabled, int requiredLevel) {
    }
}
