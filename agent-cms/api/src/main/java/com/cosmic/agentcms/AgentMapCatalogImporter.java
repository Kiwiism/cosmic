package com.cosmic.agentcms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class AgentMapCatalogImporter {
    private final JdbcTemplate jdbc;
    private final Path wzPath;

    public AgentMapCatalogImporter(JdbcTemplate jdbc, @Value("${cosmic.wz-path:../../wz}") String wzPath) {
        this.jdbc = jdbc;
        this.wzPath = Paths.get(wzPath).toAbsolutePath().normalize();
    }

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer portalCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_map_portals", Integer.class);
        Integer mapCount = jdbc.queryForObject("SELECT COUNT(DISTINCT map_id) FROM agent_map_portals", Integer.class);
        Integer footholdCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_map_footholds", Integer.class);
        Integer ladderRopeCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_map_ladder_ropes", Integer.class);
        String lastImported = jdbc.queryForObject("SELECT CAST(MAX(imported_at) AS CHAR) FROM agent_map_portals", String.class);
        result.put("portalCount", portalCount == null ? 0 : portalCount);
        result.put("footholdCount", footholdCount == null ? 0 : footholdCount);
        result.put("ladderRopeCount", ladderRopeCount == null ? 0 : ladderRopeCount);
        result.put("mapCount", mapCount == null ? 0 : mapCount);
        result.put("lastImportedAt", lastImported);
        result.put("wzPath", wzPath.toString());
        result.put("source", "Agent CMS agent_map_portals");
        return result;
    }

    Map<String, Object> importPortals() {
        Path mapRoot = wzPath.resolve("Map.wz").resolve("Map");
        if (!Files.isDirectory(mapRoot)) {
            throw new IllegalStateException("Map WZ XML folder not found: " + mapRoot);
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(mapRoot)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{9}\\.img\\.xml"))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to scan Map.wz XML files at " + mapRoot, exception);
        }

        jdbc.update("DELETE FROM agent_map_ladder_ropes");
        jdbc.update("DELETE FROM agent_map_footholds");
        jdbc.update("DELETE FROM agent_map_portals");
        ImportCounters counters = new ImportCounters();
        for (Path file : files) {
            importMapFile(file, counters);
        }

        Map<String, Object> result = status();
        result.put("importedFiles", counters.files);
        result.put("skippedFiles", files.size() - counters.files);
        result.put("importedPortals", counters.portals);
        result.put("importedFootholds", counters.footholds);
        result.put("importedLadderRopes", counters.ladderRopes);
        result.put("importedAt", Instant.now().toString());
        return result;
    }

    private void importMapFile(Path file, ImportCounters counters) {
        int mapId = mapId(file);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            int importedForMap = 0;
            int footholdsForMap = importFootholds(root, mapId, file);
            int ladderRopesForMap = importLadderRopes(root, mapId, file);
            Element portals = directDirectory(root, "portal");
            if (portals == null && footholdsForMap == 0 && ladderRopesForMap == 0) {
                return;
            }

            if (portals != null) {
                for (Element portal : childDirectories(portals)) {
                    Integer index = number(portal.getAttribute("name"));
                    if (index == null) {
                        continue;
                    }
                    Map<String, Object> values = scalarChildren(portal);
                    int portalType = valueNumber(values, "pt", 0);
                    int targetMapId = valueNumber(values, "tm", mapId);
                    String portalName = valueString(values, "pn");
                    String targetPortalName = valueString(values, "tn");
                    int x = valueNumber(values, "x", 0);
                    int y = valueNumber(values, "y", 0);

                    jdbc.update("""
                                    INSERT INTO agent_map_portals(map_id, portal_index, portal_name, portal_type,
                                                                  target_map_id, target_portal_name, x, y, source_path)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                            mapId, index, portalName, portalType, targetMapId, targetPortalName, x, y, relative(file));
                    importedForMap++;
                }
            }
            if (importedForMap > 0 || footholdsForMap > 0 || ladderRopesForMap > 0) {
                counters.files++;
                counters.portals += importedForMap;
                counters.footholds += footholdsForMap;
                counters.ladderRopes += ladderRopesForMap;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to import map catalog data from " + file, exception);
        }
    }

    private int importFootholds(Element root, int mapId, Path file) {
        Element footholdRoot = directDirectory(root, "foothold");
        if (footholdRoot == null) {
            return 0;
        }
        int imported = 0;
        for (Element layer : childDirectories(footholdRoot)) {
            int layerIndex = valueNumber(Map.of("value", layer.getAttribute("name")), "value", 0);
            for (Element group : childDirectories(layer)) {
                int groupIndex = valueNumber(Map.of("value", group.getAttribute("name")), "value", 0);
                for (Element foothold : childDirectories(group)) {
                    Integer footholdId = number(foothold.getAttribute("name"));
                    if (footholdId == null) {
                        continue;
                    }
                    Map<String, Object> values = scalarChildren(foothold);
                    if (!values.containsKey("x1") || !values.containsKey("x2")) {
                        continue;
                    }
                    jdbc.update("""
                                    INSERT INTO agent_map_footholds(map_id, foothold_id, layer_index, group_index,
                                                                    x1, y1, x2, y2, prev_foothold_id,
                                                                    next_foothold_id, forbid_fall_down, source_path)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                            mapId,
                            footholdId,
                            layerIndex,
                            groupIndex,
                            valueNumber(values, "x1", 0),
                            valueNumber(values, "y1", 0),
                            valueNumber(values, "x2", 0),
                            valueNumber(values, "y2", 0),
                            valueNumber(values, "prev", 0),
                            valueNumber(values, "next", 0),
                            valueNumber(values, "forbidFallDown", 0),
                            relative(file));
                    imported++;
                }
            }
        }
        return imported;
    }

    private int importLadderRopes(Element root, int mapId, Path file) {
        Element ladderRopeRoot = directDirectory(root, "ladderRope");
        if (ladderRopeRoot == null) {
            return 0;
        }
        int imported = 0;
        for (Element object : childDirectories(ladderRopeRoot)) {
            Integer index = number(object.getAttribute("name"));
            if (index == null) {
                continue;
            }
            Map<String, Object> values = scalarChildren(object);
            jdbc.update("""
                            INSERT INTO agent_map_ladder_ropes(map_id, object_index, is_ladder, is_upper_foothold,
                                                               x, y1, y2, page, source_path)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    mapId,
                    index,
                    valueNumber(values, "l", 0),
                    valueNumber(values, "uf", 0),
                    valueNumber(values, "x", 0),
                    valueNumber(values, "y1", 0),
                    valueNumber(values, "y2", 0),
                    valueNumber(values, "page", 0),
                    relative(file));
            imported++;
        }
        return imported;
    }

    private int mapId(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')));
    }

    private String relative(Path path) {
        try {
            return wzPath.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return path.toString().replace('\\', '/');
        }
    }

    private Element directDirectory(Element parent, String name) {
        for (Element child : childDirectories(parent)) {
            if (name.equals(child.getAttribute("name"))) {
                return child;
            }
        }
        return null;
    }

    private List<Element> childDirectories(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && "imgdir".equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private Map<String, Object> scalarChildren(Element parent) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element) || "imgdir".equals(element.getTagName())) {
                continue;
            }
            String name = element.getAttribute("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = element.getAttribute("value");
            result.put(name, parseScalar(value));
        }
        return result;
    }

    private Object parseScalar(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("-?\\d+")) {
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return Long.parseLong(trimmed);
            }
        }
        return trimmed;
    }

    private int valueNumber(Map<String, Object> values, String key, int fallback) {
        Integer value = number(values.get(key));
        return value == null ? fallback : value;
    }

    private String valueString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static class ImportCounters {
        int files;
        int portals;
        int footholds;
        int ladderRopes;
    }
}
