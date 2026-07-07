package cc.sighs.oed.asm;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class DamagePointConfig {
    private static final Path CONFIG_FILE = Paths.get("config", "OED", "oneenoughdamage.toml");
    private static final Path LEGACY_PROPERTIES_FILE = Paths.get("config", "OED", "oneenoughdamage.properties");
    private static final String READ_CACHE_KEY = "readCache";
    private static final String DEBUG_MODE_KEY = "debugMode";
    private static final String INFER_ATTRIBUTE_HOLDER_KEY = "inferAttributeHolder";
    private static final String INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY = "inferAttributeHolderSearchRadius";
    private static final boolean DEFAULT_READ_CACHE = false;
    private static final boolean DEFAULT_DEBUG_MODE = false;
    private static final boolean DEFAULT_INFER_ATTRIBUTE_HOLDER = true;
    private static final double DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS = 32.0D;
    private static final Map<String, String> VALUES = loadValues();
    private static final boolean READ_CACHE = booleanValue(READ_CACHE_KEY, DEFAULT_READ_CACHE);
    private static final boolean DEBUG_MODE = booleanValue(DEBUG_MODE_KEY, DEFAULT_DEBUG_MODE);
    private static final boolean INFER_ATTRIBUTE_HOLDER = booleanValue(INFER_ATTRIBUTE_HOLDER_KEY, DEFAULT_INFER_ATTRIBUTE_HOLDER);
    private static final double INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS = doubleValue(
            INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
            DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS
    );

    private DamagePointConfig() {
    }

    public static boolean readCache() {
        return READ_CACHE;
    }

    public static boolean debugMode() {
        return DEBUG_MODE;
    }

    public static boolean inferAttributeHolder() {
        return INFER_ATTRIBUTE_HOLDER;
    }

    public static double inferAttributeHolderSearchRadius() {
        return INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS;
    }

    private static Map<String, String> loadValues() {
        ensureConfigFile();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8)) {
                readTomlLine(line, values);
            }
        } catch (IOException ignored) {
        }
        return Map.copyOf(values);
    }

    private static void ensureConfigFile() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Map<String, String> values = Files.isRegularFile(CONFIG_FILE)
                    ? readTomlValues(CONFIG_FILE)
                    : readLegacyProperties();
            values.putIfAbsent(READ_CACHE_KEY, Boolean.toString(DEFAULT_READ_CACHE));
            values.putIfAbsent(DEBUG_MODE_KEY, Boolean.toString(DEFAULT_DEBUG_MODE));
            values.putIfAbsent(INFER_ATTRIBUTE_HOLDER_KEY, Boolean.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER));
            values.putIfAbsent(
                    INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
                    Double.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS)
            );
            Files.writeString(CONFIG_FILE, renderConfig(values), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static Map<String, String> readTomlValues(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                readTomlLine(line, values);
            }
        } catch (IOException ignored) {
        }
        return values;
    }

    private static Map<String, String> readLegacyProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(LEGACY_PROPERTIES_FILE)) {
            return values;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(LEGACY_PROPERTIES_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return values;
        }
        for (String key : properties.stringPropertyNames()) {
            values.put(key, properties.getProperty(key));
        }
        return values;
    }

    private static void readTomlLine(String line, Map<String, String> values) {
        String value = stripComment(line).trim();
        if (value.isEmpty()) {
            return;
        }
        int equals = value.indexOf('=');
        if (equals < 0) {
            return;
        }
        String key = value.substring(0, equals).trim();
        String raw = value.substring(equals + 1).trim();
        if (key.isEmpty() || raw.isEmpty()) {
            return;
        }
        values.put(key, unquote(raw));
    }

    private static String stripComment(String value) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
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
                quoted = !quoted;
                continue;
            }
            if (c == '#' && !quoted) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    private static boolean booleanValue(String key, boolean defaultValue) {
        return Boolean.parseBoolean(VALUES.getOrDefault(key, Boolean.toString(defaultValue)));
    }

    private static double doubleValue(String key, double defaultValue) {
        try {
            return Double.parseDouble(VALUES.getOrDefault(key, Double.toString(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String renderConfig(Map<String, String> values) {
        String readCache = values.getOrDefault(READ_CACHE_KEY, Boolean.toString(DEFAULT_READ_CACHE));
        String debugMode = values.getOrDefault(DEBUG_MODE_KEY, Boolean.toString(DEFAULT_DEBUG_MODE));
        String inferAttributeHolder = values.getOrDefault(
                INFER_ATTRIBUTE_HOLDER_KEY,
                Boolean.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER)
        );
        String inferAttributeHolderSearchRadius = values.getOrDefault(
                INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
                Double.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS)
        );

        return """
                # OneEnoughDamage 配置文件
                # OneEnoughDamage configuration file
                # 本文件会在游戏或服务端启动时读取。
                # This file is loaded when the game or dedicated server starts.
                # 布尔值使用 true 或 false。
                # Boolean values use true or false.

                # readCache
                # 为 true 时，如果 config/OED/damage_points-cache.json 已存在，就复用扫描缓存。
                # When true, reuse config/OED/damage_points-cache.json if it already exists.
                # 为 false 时，每次启动都会重新扫描 classpath，并重写伤害点缓存。
                # When false, rescan the classpath on every startup and rewrite the damage point cache.
                # 默认值：false
                # Default: false
                readCache = %s

                # debugMode
                # 启用用于开发和调试的 TOML 热调整逻辑。
                # Enables TOML hot-reload logic intended for development and debugging.
                # 开启后，OED 会 mixin 到 Attribute#getDefaultValue，并监听 damage-point-dictionary.toml。
                # When enabled, OED mixes into Attribute#getDefaultValue and watches damage-point-dictionary.toml.
                # 它会增量读取发生变化的 TOML 条目，并把变化的 attribute 同步到已加载实体。
                # It incrementally reads changed TOML entries and syncs changed attributes to loaded entities.
                # 这个开关本身只在启动时读取，修改它需要重启。
                # This switch itself is only read at startup, so changing it requires a restart.
                # 默认值：false
                # Default: false
                debugMode = %s

                # inferAttributeHolder
                # 当伤害点没有直接的 LivingEntity 攻击者时，尝试在附近推断归属实体。
                # When a damage point has no direct LivingEntity attacker, try to infer a nearby owner entity.
                # 这对 AI Goal、延迟效果、召唤实体等间接伤害有用。
                # This is useful for indirect damage such as AI goals, delayed effects, and summoned entities.
                # 默认值：true
                # Default: true
                inferAttributeHolder = %s

                # inferAttributeHolderSearchRadius
                # inferAttributeHolder 使用的搜索半径，单位为方块。
                # Search radius used by inferAttributeHolder, measured in blocks.
                # 数值越大越容易归属间接伤害，但也更可能选到错误的附近实体。
                # Larger values make indirect attribution easier, but also increase the chance of picking the wrong nearby entity.
                # 默认值：32.0
                # Default: 32.0
                inferAttributeHolderSearchRadius = %s
                """.formatted(readCache, debugMode, inferAttributeHolder, inferAttributeHolderSearchRadius);
    }
}
