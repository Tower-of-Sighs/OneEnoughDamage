package cc.sighs.oed.dictionary;

import cc.sighs.oed.scan.DamagePointScanResult;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

public final class MarkdownRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MarkdownRenderer() {
    }

    public static void render(String title, String subtitle, Map<String, Map<MobKey, List<DamagePointScanResult>>> groups, Path outputFile) {
        StringBuilder lines = new StringBuilder();
        lines.append("# ").append(title).append("\n\n");
        if (subtitle != null && !subtitle.isBlank()) {
            lines.append(subtitle).append("\n\n");
        }
        lines.append("This file lists scanned configurable hardcoded damage attributes by namespace and source.\n");
        lines.append("`/r` means replace original damage directly, `/m` means multiply original damage.\n\n");

        for (Map.Entry<String, Map<MobKey, List<DamagePointScanResult>>> namespaceEntry : groups.entrySet()) {
            String namespace = namespaceEntry.getKey();
            Map<MobKey, List<DamagePointScanResult>> mobs = namespaceEntry.getValue();
            if (mobs == null || mobs.isEmpty()) {
                continue;
            }
            lines.append("## ").append(capitalizeNamespace(namespace)).append("\n\n");

            for (Map.Entry<MobKey, List<DamagePointScanResult>> entry : mobs.entrySet()) {
                MobKey key = entry.getKey();
                String typeLabel = typeLabel(key.type());
                String typeSuffix = typeLabel.isEmpty() ? "" : " (Type: " + typeLabel + ")";
                if (key.enName().equals(key.zhName())) {
                    lines.append("### ").append(escapeMarkdown(key.enName())).append(typeSuffix).append("\n\n");
                } else {
                    lines.append("### ").append(escapeMarkdown(key.enName()))
                            .append(" (").append(escapeMarkdown(key.zhName())).append(")")
                            .append(typeSuffix).append("\n\n");
                }
                List<DamagePointScanResult> points = entry.getValue();
                points.sort(Comparator.comparing(DamagePointScanResult::owner)
                        .thenComparing(DamagePointScanResult::method)
                        .thenComparingInt(DamagePointScanResult::ordinal));
                for (DamagePointScanResult point : points) {
                    String mode = point.constant() ? "replace (/r)" : "multiply (/m)";
                    lines.append("- `").append(point.attribute()).append("`")
                            .append(" <!-- mode: ").append(mode)
                            .append(", default: ").append(point.defaultDamage())
                            .append(", DamageType: ").append(point.damageType())
                            .append(", ").append(point.description())
                            .append(" -->\n");
                }
                lines.append("\n");
            }
        }

        try {
            Files.createDirectories(outputFile.getParent());
            try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(lines.toString());
            }
            LOGGER.info("OED dictionary: wrote markdown to {}", outputFile);
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to write markdown", e);
        }
    }

    private static String typeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "living" -> "Living";
            case "projectile" -> "Projectile";
            case "entity" -> "Entity";
            case "item" -> "Item";
            case "block" -> "Block";
            case "effect" -> "Effect";
            case "behavior" -> "Behavior";
            default -> "Other";
        };
    }

    private static String capitalizeNamespace(String namespace) {
        String[] parts = namespace.split("_");
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

    private static String escapeMarkdown(String value) {
        return value.replace("*", "\\*").replace("_", "\\_").replace("|", "\\|");
    }
}
