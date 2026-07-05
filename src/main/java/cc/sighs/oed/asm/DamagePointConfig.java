package cc.sighs.oed.asm;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class DamagePointConfig {
    private static final Path CONFIG_FILE = Paths.get("config", "OED", "oneenoughdamage.properties");
    private static final String READ_CACHE_KEY = "readCache";
    private static final String DEBUG_MODE_KEY = "debugMode";
    private static final String INFER_ATTRIBUTE_HOLDER_KEY = "inferAttributeHolder";
    private static final String INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY = "inferAttributeHolderSearchRadius";
    private static final boolean DEFAULT_READ_CACHE = false;
    private static final boolean DEFAULT_DEBUG_MODE = false;
    private static final boolean DEFAULT_INFER_ATTRIBUTE_HOLDER = true;
    private static final double DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS = 32.0D;
    private static final Properties PROPERTIES = loadProperties();
    private static final boolean READ_CACHE = booleanProperty(READ_CACHE_KEY, DEFAULT_READ_CACHE);
    private static final boolean DEBUG_MODE = booleanProperty(DEBUG_MODE_KEY, DEFAULT_DEBUG_MODE);
    private static final boolean INFER_ATTRIBUTE_HOLDER = booleanProperty(INFER_ATTRIBUTE_HOLDER_KEY, DEFAULT_INFER_ATTRIBUTE_HOLDER);
    private static final double INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS = doubleProperty(
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

    private static Properties loadProperties() {
        ensureConfigFile();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(PROPERTIES.getProperty(key, Boolean.toString(defaultValue)));
    }

    private static double doubleProperty(String key, double defaultValue) {
        try {
            return Double.parseDouble(PROPERTIES.getProperty(key, Double.toString(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static void ensureConfigFile() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties properties = new Properties();
            if (Files.isRegularFile(CONFIG_FILE)) {
                try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            properties.putIfAbsent(READ_CACHE_KEY, Boolean.toString(DEFAULT_READ_CACHE));
            properties.putIfAbsent(DEBUG_MODE_KEY, Boolean.toString(DEFAULT_DEBUG_MODE));
            properties.putIfAbsent(INFER_ATTRIBUTE_HOLDER_KEY, Boolean.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER));
            properties.putIfAbsent(
                    INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
                    Double.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS)
            );
            Files.writeString(CONFIG_FILE, renderConfig(properties), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String renderConfig(Properties properties) {
        String readCache = properties.getProperty(READ_CACHE_KEY, Boolean.toString(DEFAULT_READ_CACHE));
        String debugMode = properties.getProperty(DEBUG_MODE_KEY, Boolean.toString(DEFAULT_DEBUG_MODE));
        String inferAttributeHolder = properties.getProperty(
                INFER_ATTRIBUTE_HOLDER_KEY,
                Boolean.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER)
        );
        String inferAttributeHolderSearchRadius = properties.getProperty(
                INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
                Double.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS)
        );

        return """
                # OneEnoughDamage settings.
                # OneEnoughDamage 配置文件。
                #
                # This file is read during game/server startup.
                # 本文件会在游戏或服务端启动时读取。
                #
                # Boolean values use true or false.
                # 布尔值使用 true 或 false。

                # readCache
                # If true, reuse config/OED/damage_points-cache.json when it already exists.
                # If false, rescan the classpath on startup and rewrite the damage point cache.
                # 如果为 true，已有 config/OED/damage_points-cache.json 时直接复用扫描缓存。
                # 如果为 false，每次启动都会重新扫描 classpath，并重写伤害点缓存。
                # Default / 默认: false
                readCache=%s

                # debugMode
                # Enables development/debug behavior for live TOML tuning.
                # When enabled, OED mixes into Attribute#getDefaultValue, watches damage-point-dictionary.toml,
                # incrementally reloads changed TOML entries, and syncs changed attributes to loaded entities.
                # 启用用于开发和调试的 TOML 热调整逻辑。
                # 开启后，OED 会 mixin 到 Attribute#getDefaultValue，监听 damage-point-dictionary.toml，
                # 增量读取发生变化的 TOML 条目，并把变化的 attribute 同步到已加载实体。
                # This switch itself is only read at startup; changing it requires a restart.
                # 这个开关本身只在启动时读取；修改它需要重启。
                # Default / 默认: false
                debugMode=%s

                # inferAttributeHolder
                # If a damage point has no direct LivingEntity attacker, try to infer the owner entity nearby.
                # This is useful for AI goals, delayed effects, summoned entities, and other indirect damage.
                # 当伤害点没有直接的 LivingEntity 攻击者时，尝试在附近推断归属实体。
                # 这对 AI Goal、延迟效果、召唤实体等间接伤害有用。
                # Default / 默认: true
                inferAttributeHolder=%s

                # inferAttributeHolderSearchRadius
                # Search radius used by inferAttributeHolder, in blocks.
                # Larger values can attribute more indirect damage, but may choose the wrong nearby entity.
                # inferAttributeHolder 使用的搜索半径，单位为方块。
                # 数值越大越容易归属间接伤害，但也更可能选到错误的附近实体。
                # Default / 默认: 32.0
                inferAttributeHolderSearchRadius=%s
                """.formatted(readCache, debugMode, inferAttributeHolder, inferAttributeHolderSearchRadius);
    }
}
