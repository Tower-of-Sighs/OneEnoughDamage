package cc.sighs.oed.asm;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class DamagePointTomlConfig {
    public static final Path CONFIG_FILE = Paths.get("config", "OED", "damage-point-dictionary.toml");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Float> VALUES = new ConcurrentHashMap<>(readValues(CONFIG_FILE));
    private static final CopyOnWriteArrayList<Consumer<Set<String>>> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile WatchService watchService;
    private static volatile boolean watcherStarted;

    private DamagePointTomlConfig() {
    }

    public static float configuredDamage(String attribute, float defaultDamage) {
        Float value = VALUES.get(attribute);
        if (value == null || !Float.isFinite(value)) {
            return defaultDamage;
        }
        return value;
    }

    public static Float configuredValue(String attribute) {
        return VALUES.get(attribute);
    }

    public static Set<String> configuredKeys() {
        return Set.copyOf(VALUES.keySet());
    }

    public static void addChangeListener(Consumer<Set<String>> listener) {
        CHANGE_LISTENERS.add(listener);
    }

    public static void startWatcherIfNeeded() {
        if (!DamagePointConfig.debugMode() || watcherStarted) {
            return;
        }
        Path parent = CONFIG_FILE.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
            WatchService service = parent.getFileSystem().newWatchService();
            parent.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            watchService = service;
            watcherStarted = true;
            Thread thread = new Thread(DamagePointTomlConfig::watchLoop, "OED TOML config watcher");
            thread.setDaemon(true);
            thread.start();
            LOGGER.info("OED debug: watching {} for live attribute default updates", CONFIG_FILE);
        } catch (IOException e) {
            LOGGER.error("OED debug: failed to start TOML watcher", e);
        }
    }

    public static Set<String> reloadIncremental() {
        Map<String, Float> latest = readValues(CONFIG_FILE);
        Set<String> changed = new HashSet<>();

        for (Map.Entry<String, Float> entry : latest.entrySet()) {
            Float previous = VALUES.get(entry.getKey());
            if (previous == null || Float.compare(previous, entry.getValue()) != 0) {
                VALUES.put(entry.getKey(), entry.getValue());
                changed.add(entry.getKey());
            }
        }

        for (String key : List.copyOf(VALUES.keySet())) {
            if (!latest.containsKey(key)) {
                VALUES.remove(key);
                changed.add(key);
            }
        }

        if (!changed.isEmpty()) {
            LOGGER.info("OED debug: reloaded {} changed TOML attribute defaults", changed.size());
            for (Consumer<Set<String>> listener : CHANGE_LISTENERS) {
                listener.accept(Set.copyOf(changed));
            }
        }
        return Set.copyOf(changed);
    }

    public static Map<String, Float> readValues(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }

        Map<String, Float> values = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String entityId = null;
            for (String line : lines) {
                String sectionEntityId = sectionEntityId(line);
                if (sectionEntityId != null) {
                    entityId = sectionEntityId.isBlank() ? null : sectionEntityId;
                    continue;
                }
                readLine(line, values, entityId);
            }
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to read toml values from {}", file, e);
        }
        return Map.copyOf(values);
    }

    private static void readLine(String line, Map<String, Float> values, String entityId) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("\"")) {
            return;
        }

        int keyEnd = findClosingQuote(trimmed);
        if (keyEnd <= 0) {
            return;
        }
        int equals = trimmed.indexOf('=', keyEnd + 1);
        if (equals < 0) {
            return;
        }

        String key = unescapeTomlString(trimmed.substring(1, keyEnd));
        if (entityId != null && !key.contains("@")) {
            key = key + "@" + entityId;
        }
        String valueText = stripComment(trimmed.substring(equals + 1).trim());
        try {
            values.put(key, Float.parseFloat(valueText));
        } catch (NumberFormatException ignored) {
        }
    }

    private static String sectionEntityId(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return null;
        }
        String section = trimmed.substring(1, trimmed.length() - 1).trim();
        if (section.startsWith("entity.\"") && section.endsWith("\"")) {
            return unescapeTomlString(section.substring("entity.\"".length(), section.length() - 1));
        }
        if (section.equals("entity") || !section.startsWith("entity.")) {
            return "";
        }
        return section.substring("entity.".length());
    }

    private static int findClosingQuote(String value) {
        boolean escaped = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String value) {
        int comment = value.indexOf('#');
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    private static String unescapeTomlString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void watchLoop() {
        while (watcherStarted && watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException ignored) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            boolean reload = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.context() instanceof Path changed
                        && CONFIG_FILE.getFileName().equals(changed)
                        && event.kind() != StandardWatchEventKinds.OVERFLOW) {
                    reload = true;
                }
            }
            if (!key.reset()) {
                return;
            }
            if (reload) {
                debounceAndReload();
            }
        }
    }

    private static void debounceAndReload() {
        try {
            Thread.sleep(150L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        reloadIncremental();
    }
}
