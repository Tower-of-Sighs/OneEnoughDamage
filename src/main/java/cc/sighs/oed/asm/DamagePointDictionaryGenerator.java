package cc.sighs.oed.asm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Generates a markdown dictionary from the scanned damage point cache.
 * Runs during common setup so that registries and language data are available.
 */
public final class DamagePointDictionaryGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CACHE_FILE = Paths.get("config", "OED", "damage_points-cache.json");

    private static Path outputFile() {
        return Paths.get("config", "OED", "damage-point-dictionary.md");
    }

    private static final Map<String, String> MOD_TITLES = Map.of(
            "minecraft", "Minecraft（原版）",
            "cataclysm", "L_Ender's Cataclysm（灾变）",
            "illagerinvasion", "Illager Invasion（灾厄入侵）"
    );

    /**
     * Classes that are not registry objects themselves but whose damage points belong to a known
     * registry object. Maps owner class name to that object's translation key.
     */
    private static final Map<String, String> SPECIAL_OWNER_KEYS = Map.of(
            "net.minecraft.world.entity.ai.behavior.warden.SonicBoom", "entity.minecraft.warden"
    );

    /**
     * Maps a damage-point owner class (usually a projectile/effect entity) to the translation keys of
     * the living entities that create or fire it. A single projectile can belong to multiple owners.
     */
    private static final Map<String, List<String>> OWNER_OVERRIDES = Map.ofEntries(
            Map.entry("net.minecraft.world.entity.projectile.AbstractArrow", List.of(
                    "entity.minecraft.skeleton", "entity.minecraft.stray", "entity.minecraft.bogged", "entity.minecraft.wither_skeleton")),
            Map.entry("net.minecraft.world.entity.projectile.LargeFireball", List.of("entity.minecraft.ghast")),
            Map.entry("net.minecraft.world.entity.projectile.SmallFireball", List.of("entity.minecraft.blaze")),
            Map.entry("net.minecraft.world.entity.projectile.WitherSkull", List.of("entity.minecraft.wither")),
            Map.entry("net.minecraft.world.entity.projectile.ShulkerBullet", List.of("entity.minecraft.shulker")),
            Map.entry("net.minecraft.world.entity.projectile.LlamaSpit", List.of("entity.minecraft.llama")),
            Map.entry("net.minecraft.world.entity.projectile.EvokerFangs", List.of("entity.minecraft.evoker")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Ender_Guardian_Bullet_Entity", List.of("entity.cataclysm.ender_guardian")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Ignis_Fireball_Entity", List.of("entity.cataclysm.ignis")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Ignis_Abyss_Fireball_Entity", List.of("entity.cataclysm.ignis")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Wither_Missile_Entity", List.of("entity.cataclysm.the_harbinger")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Wither_Homing_Missile_Entity", List.of("entity.cataclysm.the_harbinger")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Wither_Howitzer_Entity", List.of("entity.cataclysm.the_harbinger")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Lionfish_Spike_Entity", List.of("entity.cataclysm.lionfish")),
            Map.entry("com.github.L_Ender.cataclysm.entity.projectile.Mini_Abyss_Blast_Entity", List.of("entity.cataclysm.the_leviathan")),
            Map.entry("com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.Abyss_Blast_Entity", List.of("entity.cataclysm.the_leviathan")),
            Map.entry("com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.Abyss_Orb_Entity", List.of("entity.cataclysm.the_leviathan")),
            Map.entry("com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.Dimensional_Rift_Entity", List.of("entity.cataclysm.the_leviathan")),
            Map.entry("com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.Portal_Abyss_Blast_Entity", List.of("entity.cataclysm.the_leviathan")),
            Map.entry("com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.The_Leviathan_Tongue_Entity", List.of("entity.cataclysm.the_leviathan"))
    );

    private static EntityCreatorAnalyzer analyzer;
    private static Map<String, Set<String>> creatorIndex;

    private static EntityCreatorAnalyzer analyzer() {
        if (analyzer == null) {
            long start = System.nanoTime();
            analyzer = new EntityCreatorAnalyzer();
            creatorIndex = analyzer.analyze();
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.info("OED creator analysis finished in {} ms, {} created classes found", elapsed, creatorIndex.size());
        }
        return analyzer;
    }

    private static Map<String, Set<String>> creatorIndex() {
        analyzer();
        return creatorIndex;
    }

    private DamagePointDictionaryGenerator() {
    }

    public static void generateIfNeeded() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            LOGGER.info("OED dictionary: cache not found, skipping");
            return;
        }
        try {
            Path outputFile = outputFile();
            if (Files.isRegularFile(outputFile)
                    && Files.getLastModifiedTime(outputFile).toMillis() >= Files.getLastModifiedTime(CACHE_FILE).toMillis()) {
                LOGGER.info("OED dictionary: up to date");
                return;
            }
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to compare file times", e);
            return;
        }
        generate();
    }

    private static void generate() {
        long start = System.nanoTime();
        List<DamagePoint> points = readCache();
        if (points.isEmpty()) {
            return;
        }

        Map<String, Map<MobKey, List<DamagePoint>>> groups = new TreeMap<>();
        for (DamagePoint point : points) {
            if (!point.attributeCandidate) {
                continue;
            }
            String namespace = detectNamespace(point.owner);
            List<MobKey> keys = resolveOwners(point.owner, namespace);
            for (MobKey key : keys) {
                groups.computeIfAbsent(namespace, k -> new TreeMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>())
                        .add(point);
            }
        }

        writeMarkdown(groups);
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.info("OED dictionary generated at {} in {} ms", outputFile(), elapsed);
    }

    private static List<DamagePoint> readCache() {
        try (InputStream input = Files.newInputStream(CACHE_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<DamagePoint> points = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("points")) {
                JsonObject object = element.getAsJsonObject();
                points.add(new DamagePoint(
                        getString(object, "owner"),
                        getString(object, "method"),
                        getString(object, "descriptor"),
                        getInt(object, "ordinal"),
                        getFloat(object, "default"),
                        getString(object, "damageSource"),
                        getString(object, "attribute"),
                        getString(object, "description"),
                        getBoolean(object, "attributeCandidate"),
                        getBoolean(object, "constant")
                ));
            }
            return points;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("OED dictionary: failed to read cache", e);
            return List.of();
        }
    }

    private static List<MobKey> resolveOwners(String owner, String namespace) {
        List<String> overrideKeys = OWNER_OVERRIDES.get(owner);
        if (overrideKeys != null) {
            List<MobKey> result = new ArrayList<>();
            for (String key : overrideKeys) {
                result.add(keyFromDescriptionId(key, namespace, classNameOf(owner)));
            }
            return result;
        }

        String specialKey = SPECIAL_OWNER_KEYS.get(owner);
        if (specialKey != null) {
            return List.of(keyFromDescriptionId(specialKey, namespace, classNameOf(owner)));
        }

        if ("net.minecraft.world.effect.MobEffect".equals(owner)) {
            return List.of(new MobKey("Mob Effect", "Mob Effect", classNameOf(owner)));
        }

        if (isSpawnableEntity(owner)) {
            Set<String> creators = findCreators(owner);
            if (!creators.isEmpty()) {
                List<MobKey> result = new ArrayList<>();
                for (String creator : creators) {
                    MobKey key = resolveDirectOwner(creator, namespace);
                    if (key != null) {
                        result.add(key);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        MobKey direct = resolveDirectOwner(owner, namespace);
        if (direct != null) {
            return List.of(direct);
        }

        MobKey heuristic = resolveOwnerByHeuristic(owner, namespace);
        if (heuristic != null) {
            return List.of(heuristic);
        }

        return List.of(new MobKey(classNameOf(owner), classNameOf(owner), classNameOf(owner)));
    }

    private static String classNameOf(String owner) {
        String outer = owner.split("\\$")[0];
        return outer.substring(outer.lastIndexOf('.') + 1);
    }

    private static boolean isSpawnableEntity(String className) {
        if (className == null) {
            return false;
        }
        return className.startsWith("net.minecraft.world.entity.projectile.")
                || className.contains(".entity.projectile.")
                || className.startsWith("net.minecraft.world.entity.effect.")
                || className.contains(".entity.effect.");
    }

    private static Set<String> findCreators(String created) {
        Set<String> creators = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(created);
        EntityCreatorAnalyzer analyzer = analyzer();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            Set<String> direct = creatorIndex().get(current);
            if (direct == null) {
                continue;
            }
            for (String creator : direct) {
                if (isSpawnableEntity(creator)) {
                    queue.add(creator);
                } else if (analyzer.isLivingEntity(creator)) {
                    creators.add(creator);
                }
            }
        }
        return creators;
    }

    private static MobKey resolveDirectOwner(String owner, String namespace) {
        String className = classNameOf(owner);

        String descriptionId = RegistryIndex.findBySuffix(owner);
        if (descriptionId != null) {
            return keyFromDescriptionId(descriptionId, namespace, className);
        }

        return resolveFromLanguage(className, namespace);
    }

    private static MobKey resolveOwnerByHeuristic(String owner, String namespace) {
        if (!isLikelyProjectileOrEffect(owner)) {
            return null;
        }

        String className = classNameOf(owner);
        String base = className;
        for (String suffix : List.of("_Entity", "Projectile", "Bullet", "Fireball", "Missile", "Howitzer", "Spear", "Bomb", "Shard", "Rune", "Blast", "Orb", "Spike", "Hook", "Arrow", "Breath", "Strike", "Storm", "Effect", "Area", "Vortex", "Mine", "Portal", "Tentacle", "Wave", "Ink")) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        if (base.isBlank() || base.equals(className)) {
            return null;
        }

        String snake = camelToSnake(base);
        String key = "entity." + namespace + "." + snake;
        Map<String, String> en = loadLanguage(namespace, "en_us");
        if (en.containsKey(key)) {
            Map<String, String> zh = loadLanguage(namespace, "zh_cn");
            String enName = en.get(key);
            String zhName = zh.getOrDefault(key, enName);
            return new MobKey(enName, zhName, className);
        }
        return null;
    }

    private static boolean isLikelyProjectileOrEffect(String owner) {
        return owner.contains(".entity.projectile.") || owner.contains(".entity.effect.");
    }

    private static final class RegistryIndex {
        private static final Map<String, EntityType<?>> ENTITY_TYPES = new LinkedHashMap<>();
        private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
        private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
        private static final Map<String, MobEffect> EFFECTS = new LinkedHashMap<>();

        static {
            for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES.getValues()) {
                ENTITY_TYPES.putIfAbsent(lastSegment(type.getDescriptionId()), type);
            }
            for (Block block : ForgeRegistries.BLOCKS.getValues()) {
                BLOCKS.putIfAbsent(lastSegment(block.getDescriptionId()), block);
            }
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                ITEMS.putIfAbsent(lastSegment(item.getDescriptionId()), item);
            }
            for (MobEffect effect : ForgeRegistries.MOB_EFFECTS.getValues()) {
                EFFECTS.putIfAbsent(lastSegment(effect.getDescriptionId()), effect);
            }
        }

        private static String lastSegment(String id) {
            int dot = id.lastIndexOf('.');
            return dot >= 0 ? id.substring(dot + 1) : id;
        }

        static String findBySuffix(Class<?> clazz) {
            return findBySuffix(clazz.getSimpleName());
        }

        static String findBySuffix(String owner) {
            String outer = owner.split("\\$")[0];
            String className = outer.substring(outer.lastIndexOf('.') + 1);
            for (String variant : classNameVariants(className)) {
                String snake = camelToSnake(variant);

                EntityType<?> entityType = ENTITY_TYPES.get(snake);
                if (entityType != null) {
                    return entityType.getDescriptionId();
                }

                Block block = BLOCKS.get(snake);
                if (block != null) {
                    return block.getDescriptionId();
                }

                Item item = ITEMS.get(snake);
                if (item != null) {
                    return item.getDescriptionId();
                }

                MobEffect effect = EFFECTS.get(snake);
                if (effect != null) {
                    return effect.getDescriptionId();
                }
            }
            return null;
        }
    }

    private static MobKey keyFromDescriptionId(String descriptionId, String namespace, String fallbackClass) {
        int lastDot = descriptionId.lastIndexOf('.');
        String keyName = lastDot >= 0 ? descriptionId.substring(lastDot + 1) : descriptionId;
        Map<String, String> en = loadLanguage(namespace, "en_us");
        Map<String, String> zh = loadLanguage(namespace, "zh_cn");
        String enName = en.getOrDefault(descriptionId, formatName(keyName));
        String zhName = zh.getOrDefault(descriptionId, enName);
        return new MobKey(enName, zhName, fallbackClass);
    }

    private static MobKey resolveFromLanguage(String className, String namespace) {
        Map<String, String> en = loadLanguage(namespace, "en_us");
        Map<String, String> zh = loadLanguage(namespace, "zh_cn");

        for (String variant : classNameVariants(className)) {
            String snake = camelToSnake(variant);
            for (String prefix : List.of("entity", "block", "effect", "item")) {
                String key = prefix + "." + namespace + "." + snake;
                if (en.containsKey(key)) {
                    String enName = en.get(key);
                    String zhName = zh.getOrDefault(key, enName);
                    return new MobKey(enName, zhName, className);
                }
            }
        }
        return new MobKey(className, className, className);
    }

    private static List<String> classNameVariants(String className) {
        List<String> variants = new ArrayList<>();
        String base = className;
        for (String suffix : List.of("_Entity", "Block_Entity")) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        variants.add(base);
        if (className.startsWith("Effect") && className.length() > 6) {
            variants.add(className.substring(6));
        }
        if (className.endsWith("_Block") && className.length() > 6) {
            variants.add(className.substring(0, className.length() - 6));
        }
        return variants;
    }

    private static String camelToSnake(String value) {
        String result = value.replaceAll("(.)([A-Z][a-z]+)", "$1_$2");
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        result = result.toLowerCase(Locale.ROOT);
        result = result.replaceAll("_+", "_");
        return result.replaceAll("^_+|_+$", "");
    }

    private static String formatName(String snake) {
        String[] parts = snake.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static Map<String, String> loadLanguage(String namespace, String langCode) {
        if ("minecraft".equals(namespace)) {
            return loadVanillaLanguage(langCode);
        }
        return loadModLanguage(namespace, langCode);
    }

    private static Map<String, String> loadModLanguage(String namespace, String langCode) {
        String path = "assets/" + namespace + "/lang/" + langCode + ".json";
        try (InputStream input = DamagePointDictionaryGenerator.class.getClassLoader().getResourceAsStream(path)) {
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
        String indexId = vanillaAssetIndexId();
        if (indexId == null) {
            return null;
        }
        Path indexFile = objectsDir.resolveSibling("indexes").resolve(indexId + ".json");
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
            LOGGER.debug("OED dictionary: failed to read asset index for {}", assetPath);
            return null;
        }
    }

    private static Path assetObjectsDir() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.isBlank()) {
            gradleHome = System.getProperty("user.home") + "\\.gradle";
        }
        Path path = Paths.get(gradleHome, "caches", "forge_gradle", "assets", "objects");
        return Files.isDirectory(path) ? path : null;
    }

    private static String vanillaAssetIndexId() {
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        Path versionJson = assetObjectsDir().resolveSibling("..").resolve("minecraft_repo").resolve("versions").resolve(mcVersion).resolve("version.json");
        if (!Files.isRegularFile(versionJson)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(versionJson);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject assetIndex = root.getAsJsonObject("assetIndex");
            return assetIndex == null ? null : assetIndex.get("id").getAsString();
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

    private static String detectNamespace(String owner) {
        if (owner.startsWith("fuzs.illagerinvasion.")) {
            return "illagerinvasion";
        }
        if (owner.startsWith("com.github.L_Ender.cataclysm.")) {
            return "cataclysm";
        }
        return "minecraft";
    }

    private static void writeMarkdown(Map<String, Map<MobKey, List<DamagePoint>>> groups) {
        StringBuilder lines = new StringBuilder();
        lines.append("# OneEnoughDamage 硬编码伤害点字典\n\n");
        lines.append("本文件按模组和生物列出扫描到的可配置硬编码伤害属性 ID。`〇/r` 表示该属性直接替换原伤害，`〇/m` 表示该属性作为乘数（原伤害 × 属性值）。\n\n");

        for (String namespace : List.of("minecraft", "cataclysm", "illagerinvasion")) {
            Map<MobKey, List<DamagePoint>> mobs = groups.get(namespace);
            if (mobs == null || mobs.isEmpty()) {
                continue;
            }
            lines.append("## ").append(MOD_TITLES.getOrDefault(namespace, namespace)).append("\n\n");

            for (Map.Entry<MobKey, List<DamagePoint>> entry : mobs.entrySet()) {
                MobKey key = entry.getKey();
                if (key.enName.equals(key.zhName)) {
                    lines.append("### ").append(escapeMarkdown(key.enName)).append("\n\n");
                } else {
                    lines.append("### ").append(escapeMarkdown(key.enName))
                            .append("（").append(escapeMarkdown(key.zhName)).append("）\n\n");
                }
                List<DamagePoint> points = entry.getValue();
                points.sort(Comparator.comparing((DamagePoint p) -> p.owner)
                        .thenComparing(p -> p.method)
                        .thenComparingInt(p -> p.ordinal));
                for (DamagePoint point : points) {
                    String mode = point.constant ? "替换（r）" : "乘数（m）";
                    lines.append("1. `").append(point.attribute).append("`  <!-- 模式：").append(mode)
                            .append("，默认 ").append(point.defaultDamage).append("，伤害源 ")
                            .append(point.damageSource).append("，")
                            .append(point.description).append(" -->\n");
                }
                lines.append("\n");
            }
        }

        try {
            Path outputFile = outputFile();
            Files.createDirectories(outputFile.getParent());
            try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(lines.toString());
            }
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to write markdown", e);
        }
    }

    private static String escapeMarkdown(String value) {
        return value.replace("*", "\\*").replace("_", "\\_").replace("|", "\\|");
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static int getInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
    }

    private static float getFloat(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsFloat() : 0.0F;
    }

    private static boolean getBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private record MobKey(String enName, String zhName, String fallback) implements Comparable<MobKey> {
        @Override
        public int compareTo(MobKey other) {
            return enName.compareToIgnoreCase(other.enName);
        }
    }

    private record DamagePoint(
            String owner,
            String method,
            String descriptor,
            int ordinal,
            float defaultDamage,
            String damageSource,
            String attribute,
            String description,
            boolean attributeCandidate,
            boolean constant
    ) {
    }
}
