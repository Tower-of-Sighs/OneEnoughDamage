package cc.sighs.oed.asm;

import cc.sighs.oed.dictionary.MarkdownRenderer;
import cc.sighs.oed.dictionary.MobKey;
import cc.sighs.oed.dictionary.OwnerResolver;
import cc.sighs.oed.dictionary.ResolvedOwner;
import cc.sighs.oed.dictionary.TomlDictionaryRenderer;
import cc.sighs.oed.scan.DamagePointScanResult;
import cc.sighs.oed.scan.DamagePointScanner;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/**
 * Generates config dictionaries from the scanned damage point cache.
 * Runs during common setup so that registries and language data are available.
 */
public final class DamagePointDictionaryGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CACHE_FILE = DamagePointScanner.cacheFile();
    private static final Path DICTIONARY_FILE = Paths.get("config", "OED", "damage-point-dictionary.md");
    private static final Path CONFIG_FILE = DamagePointTomlConfig.CONFIG_FILE;
    private static final Path UNATTRIBUTED_FILE = Paths.get("config", "OED", "damage-point-unattributed.md");

    private static OwnerResolver ownerResolver;

    private DamagePointDictionaryGenerator() {
    }

    public static void generateIfNeeded() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            LOGGER.info("OED dictionary: cache not found, skipping");
            return;
        }
        try {
            long cacheTime = Files.getLastModifiedTime(CACHE_FILE).toMillis();
            if (isUpToDate(DICTIONARY_FILE, cacheTime)
                    && isUpToDate(CONFIG_FILE, cacheTime)
                    && isUpToDate(UNATTRIBUTED_FILE, cacheTime)) {
                LOGGER.info("OED dictionary: up to date");
                return;
            }
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to compare file times", e);
            return;
        }
        generate();
    }

    private static boolean isUpToDate(Path file, long cacheTime) {
        try {
            return Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() >= cacheTime;
        } catch (IOException e) {
            return false;
        }
    }

    private static void generate() {
        long start = System.nanoTime();
        List<DamagePointScanResult> points = DamagePointScanner.withUniqueAttributes(DamagePointScanner.readCache());
        if (points.isEmpty()) {
            LOGGER.warn("OED dictionary: cache exists but contains no points, skipping generation");
            return;
        }

        OwnerResolver resolver = ownerResolver();
        Map<String, Map<MobKey, List<DamagePointScanResult>>> attributed = new TreeMap<>();
        Map<String, Map<MobKey, List<DamagePointScanResult>>> unattributed = new TreeMap<>();
        for (DamagePointScanResult point : points) {
            List<ResolvedOwner> owners = resolver.resolveOwners(point.owner());
            Map<String, Map<MobKey, List<DamagePointScanResult>>> target =
                    resolver.canAttributeToLivingEntity(point.owner()) ? attributed : unattributed;
            for (ResolvedOwner owner : owners) {
                target.computeIfAbsent(owner.namespace(), k -> new TreeMap<>())
                        .computeIfAbsent(owner.key(), k -> new ArrayList<>())
                        .add(point);
            }
        }

        MarkdownRenderer.render("OneEnoughDamage 硬编码伤害点字典", null, attributed, DICTIONARY_FILE);
        TomlDictionaryRenderer.render(attributed, CONFIG_FILE);
        MarkdownRenderer.render("OneEnoughDamage 未归属伤害点汇总",
                "以下伤害点无法追溯到某个 LivingEntity。每个条目标注了其最终来源类型（物品、弹射物、方块、效果等），运行时通常通过 Projectile Base Damage 或其他全局机制生效。",
                unattributed, UNATTRIBUTED_FILE);
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.info(
                "OED dictionary generated at {}, {}, and {} in {} ms",
                DICTIONARY_FILE,
                CONFIG_FILE,
                UNATTRIBUTED_FILE,
                elapsed
        );
    }

    private static OwnerResolver ownerResolver() {
        if (ownerResolver == null) {
            ownerResolver = new OwnerResolver(new EntityCreatorAnalyzer());
        }
        return ownerResolver;
    }

}
