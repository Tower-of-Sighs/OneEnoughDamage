package cc.sighs.oed.asm;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class DamagePointConfig {
    private static final Path CONFIG_FILE = Paths.get("config", "OEB", "oneenoughdamage.properties");
    private static final String READ_CACHE_KEY = "readCache";
    private static final String INFER_ATTRIBUTE_HOLDER_KEY = "inferAttributeHolder";
    private static final String INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY = "inferAttributeHolderSearchRadius";
    private static final boolean DEFAULT_READ_CACHE = false;
    private static final boolean DEFAULT_INFER_ATTRIBUTE_HOLDER = true;
    private static final double DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS = 32.0D;
    private static final Properties PROPERTIES = loadProperties();
    private static final boolean READ_CACHE = booleanProperty(READ_CACHE_KEY, DEFAULT_READ_CACHE);
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
            properties.putIfAbsent(INFER_ATTRIBUTE_HOLDER_KEY, Boolean.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER));
            properties.putIfAbsent(
                    INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS_KEY,
                    Double.toString(DEFAULT_INFER_ATTRIBUTE_HOLDER_SEARCH_RADIUS)
            );
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                properties.store(writer, "OneEnoughDamage settings.");
            }
        } catch (IOException ignored) {
        }
    }
}
