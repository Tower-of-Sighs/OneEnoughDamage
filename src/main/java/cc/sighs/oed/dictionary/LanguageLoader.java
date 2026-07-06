package cc.sighs.oed.dictionary;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;

public final class LanguageLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LanguageLoader() {
    }

    public static Map<String, String> loadLanguage(String namespace, String langCode) {
        return loadModLanguage(namespace, langCode);
    }

    private static Map<String, String> loadModLanguage(String namespace, String langCode) {
        String path = "assets/" + namespace + "/lang/" + langCode + ".json";
        try (InputStream input = LanguageLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (input != null) {
                return parseLanguage(input);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to load language {} {} from classpath", namespace, langCode);
        }

        for (IModInfo mod : ModList.get().getMods()) {
            if (!namespace.equals(mod.getModId())) {
                continue;
            }
            Path resource = mod.getOwningFile().getFile().findResource(path);
            try (InputStream input = resource.toUri().toURL().openStream()) {
                return parseLanguage(input);
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("OED dictionary: failed to load language {} {} from mod file", namespace, langCode);
            }
        }
        return Map.of();
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
