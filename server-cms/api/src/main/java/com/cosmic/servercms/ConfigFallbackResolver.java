package com.cosmic.servercms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConfigFallbackResolver {
    private final Path configFile;

    public ConfigFallbackResolver(@Value("${cosmic.project-path:.}") String projectPath) {
        configFile = Path.of(projectPath).toAbsolutePath().normalize().resolve("config.yaml");
    }

    Optional<String> resolve(Map<String, Object> setting, int scopeId) {
        if (!"YAML_EXISTING".equals(String.valueOf(setting.get("origin_type")))) {
            return Optional.empty();
        }

        String symbol = String.valueOf(setting.get("source_symbol"));
        if (symbol == null || symbol.isBlank() || "null".equals(symbol)) {
            return Optional.empty();
        }

        try {
            Map<String, Object> root = readYaml();
            Object value = resolveSymbol(root, symbol, scopeId);
            return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolveSymbol(Map<String, Object> root, String symbol, int scopeId) {
        if (symbol.startsWith("server.")) {
            Object server = root.get("server");
            if (!(server instanceof Map<?, ?> serverMap)) {
                return null;
            }
            return ((Map<String, Object>) serverMap).get(symbol.substring("server.".length()));
        }

        if (symbol.startsWith("worlds[].")) {
            Object worlds = root.get("worlds");
            if (!(worlds instanceof List<?> worldList) || scopeId < 0 || scopeId >= worldList.size()) {
                return null;
            }
            Object world = worldList.get(scopeId);
            if (!(world instanceof Map<?, ?> worldMap)) {
                return null;
            }
            return ((Map<String, Object>) worldMap).get(symbol.substring("worlds[].".length()));
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml() throws IOException {
        try (InputStream input = Files.newInputStream(configFile)) {
            Object loaded = new Yaml().load(input);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }
}
