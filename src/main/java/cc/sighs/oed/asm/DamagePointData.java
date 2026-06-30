package cc.sighs.oed.asm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DamagePointData {
    private static final Path CACHE_FILE = Paths.get("config", "OED", "damage_points-cache.json");
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);

    private DamagePointData() {
    }

    public static List<DamagePoint> points() {
        ensureCache();
        if (!Files.isRegularFile(CACHE_FILE)) {
            return List.of();
        }

        try {
            String json = Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            List<DamagePoint> points = new ArrayList<>();
            Matcher matcher = OBJECT_PATTERN.matcher(json);
            while (matcher.find()) {
                String object = matcher.group(1);
                if (!booleanField(object, "attributeCandidate")) {
                    continue;
                }

                String owner = stringField(object, "owner");
                String method = stringField(object, "method");
                String descriptor = stringField(object, "descriptor");
                int ordinal = (int) numberField(object, "ordinal");
                float defaultDamage = (float) numberField(object, "default");
                String damageSource = stringField(object, "damageSource");
                String attribute = stringField(object, "attribute");
                String description = stringField(object, "description");
                if (owner == null || method == null || descriptor == null || damageSource == null || attribute == null || description == null || ordinal <= 0) {
                    continue;
                }

                points.add(new DamagePoint(
                        owner,
                        method,
                        descriptor,
                        ordinal,
                        defaultDamage,
                        damageSource,
                        stripNamespace(attribute),
                        description
                ));
            }
            points.sort(Comparator.comparing(DamagePoint::owner).thenComparing(DamagePoint::method).thenComparingInt(DamagePoint::ordinal));
            return points;
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static void ensureCache() {
        if (!DamagePointConfig.readCache() || !Files.exists(CACHE_FILE)) {
            new DamagePointMixinPlugin().onLoad("cc.sighs.oed.mixin");
        }
    }

    private static String stripNamespace(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    private static String stringField(String object, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(object);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static double numberField(String object, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*([-0-9.E]+)").matcher(object);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0D;
    }

    private static boolean booleanField(String object, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(true|false)").matcher(object);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    public record DamagePoint(String owner, String method, String descriptor, int ordinal, float defaultDamage, String damageSource, String attributePath, String description) {
    }
}
