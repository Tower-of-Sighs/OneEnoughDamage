package cc.sighs.oed.asm;

import cc.sighs.oed.scan.DamagePointScanResult;
import cc.sighs.oed.scan.DamagePointScanner;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public final class DamagePointData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CACHE_FILE = DamagePointScanner.cacheFile();

    private DamagePointData() {
    }

    public static List<DamagePoint> points() {
        ensureCache();
        if (!Files.isRegularFile(CACHE_FILE)) {
            return List.of();
        }

        List<DamagePointScanResult> scanResults = DamagePointScanner.withUniqueAttributes(DamagePointScanner.readCache());
        Map<String, DamagePoint> deduplicatedPoints = new LinkedHashMap<>(scanResults.size());
        for (DamagePointScanResult result : scanResults) {
            if (result.defaultDamage() == 0.0F || result.defaultDamage() == Float.MAX_VALUE) {
                continue;
            }
            String attributePath = stripNamespace(result.attribute());
            DamagePoint point = new DamagePoint(
                    result.owner(),
                    result.method(),
                    result.descriptor(),
                    result.ordinal(),
                    DamagePointTomlConfig.configuredDamage(result.attribute(), result.defaultDamage()),
                    attributePath,
                    result.description(),
                    result.constant()
            );
            DamagePoint previous = deduplicatedPoints.putIfAbsent(attributePath, point);
            if (previous != null) {
                LOGGER.warn(
                        "OED skipped duplicate damage point attribute {} from {}#{}#{}, already registered by {}#{}#{}",
                        attributePath,
                        point.owner(),
                        point.method(),
                        point.ordinal(),
                        previous.owner(),
                        previous.method(),
                        previous.ordinal()
                );
            }
        }
        List<DamagePoint> points = new ArrayList<>(deduplicatedPoints.values());
        points.sort(Comparator.comparing(DamagePoint::owner)
                .thenComparing(DamagePoint::method)
                .thenComparingInt(DamagePoint::ordinal));
        return points;
    }

    private static void ensureCache() {
        if (!DamagePointConfig.readCache() || !Files.exists(CACHE_FILE)) {
            DamagePointScanner.scanAndWriteCacheIfNeeded();
        }
    }

    private static String stripNamespace(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    public record DamagePoint(String owner, String method, String descriptor, int ordinal, float defaultDamage, String attributePath, String description, boolean constant) {
    }
}
