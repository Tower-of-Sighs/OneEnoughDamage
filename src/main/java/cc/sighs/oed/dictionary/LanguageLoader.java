package cc.sighs.oed.dictionary;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;

public final class LanguageLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LanguageLoader() {
    }

    public static Map<String, String> loadLanguage(String namespace, String langCode) {
        if ("minecraft".equals(namespace)) {
            return loadVanillaLanguage(langCode);
        }
        return loadModLanguage(namespace, langCode);
    }

    private static Map<String, String> loadModLanguage(String namespace, String langCode) {
        String path = "assets/" + namespace + "/lang/" + langCode + ".json";
        try (InputStream input = LanguageLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                return Map.of();
            }
            return parseLanguage(input);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to load language {} {}", namespace, langCode);
            return Map.of();
        }
    }

    private static Map<String, String> loadVanillaLanguage(String langCode) {
        String assetPath = "minecraft/lang/" + langCode + ".json";
        Path file = findAssetFile(assetPath);
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream input = Files.newInputStream(file)) {
            return parseLanguage(input);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to load vanilla language {}", langCode);
            return Map.of();
        }
    }

    private static Path findAssetFile(String assetPath) {
        Path objectsDir = assetObjectsDir();
        if (objectsDir == null) {
            return null;
        }

        Path indexFile = vanillaAssetIndexFile(objectsDir);
        Path result = readAssetFromIndex(indexFile, objectsDir, assetPath);
        if (result != null) {
            return result;
        }

        Path indexesDir = objectsDir.resolveSibling("indexes");
        if (!Files.isDirectory(indexesDir)) {
            return null;
        }
        try (Stream<Path> indexes = Files.list(indexesDir)) {
            for (Path candidate : indexes.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                result = readAssetFromIndex(candidate, objectsDir, assetPath);
                if (result != null) {
                    return result;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Path readAssetFromIndex(Path indexFile, Path objectsDir, String assetPath) {
        if (indexFile == null) {
            return null;
        }
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(indexFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject objects = root.getAsJsonObject("objects");
            JsonObject entry = objects == null ? null : objects.getAsJsonObject(assetPath);
            if (entry == null) {
                return null;
            }
            String hash = entry.get("hash").getAsString();
            return objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to read asset index {} for {}", indexFile, assetPath);
            return null;
        }
    }

    private static Path assetObjectsDir() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.isBlank()) {
            gradleHome = Paths.get(System.getProperty("user.home"), ".gradle").toString();
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(gradleHome, "caches", "neoformruntime", "assets", "objects"));
        candidates.add(Paths.get(gradleHome, "caches", "forge_gradle", "assets", "objects"));
        candidates.add(Paths.get(gradleHome, "caches", "minecraft", "assets", "objects"));
        for (Path path : candidates) {
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    private static Path vanillaAssetIndexFile(Path objectsDir) {
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        Path neoFormVersion = objectsDir.resolveSibling("..").resolve("artifacts").resolve("minecraft_" + mcVersion + "_version_manifest.json").normalize();
        Path forgeVersion = objectsDir.resolveSibling("..").resolve("minecraft_repo").resolve("versions").resolve(mcVersion).resolve("version.json").normalize();
        Path versionJson = Files.isRegularFile(neoFormVersion) ? neoFormVersion : forgeVersion;
        try (InputStream input = Files.newInputStream(versionJson);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject assetIndex = root.getAsJsonObject("assetIndex");
            if (assetIndex == null) {
                return null;
            }
            return objectsDir.resolveSibling("indexes").resolve(assetIndex.get("id").getAsString() + ".json");
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to read version.json", e);
            return null;
        }
    }

    private static Map<String, String> parseLanguage(InputStream input) throws IOException {
        JsonObject object = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return map;
    }
}
