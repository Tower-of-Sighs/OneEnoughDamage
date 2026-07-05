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
        lines.append("本文件按模组和来源列出扫描到的可配置硬编码伤害属性 ID。`〇/r` 表示该属性直接替换原伤害，`〇/m` 表示该属性作为乘数（原伤害 × 属性值）。\n\n");

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
                String typeSuffix = typeLabel.isEmpty() ? "" : "（类型：" + typeLabel + "）";
                if (key.enName().equals(key.zhName())) {
                    lines.append("### ").append(escapeMarkdown(key.enName())).append(typeSuffix).append("\n\n");
                } else {
                    lines.append("### ").append(escapeMarkdown(key.enName()))
                            .append("（").append(escapeMarkdown(key.zhName())).append("）").append(typeSuffix).append("\n\n");
                }
                List<DamagePointScanResult> points = entry.getValue();
                points.sort(Comparator.comparing((DamagePointScanResult p) -> p.owner())
                        .thenComparing(p -> p.method())
                        .thenComparingInt(p -> p.ordinal()));
                for (DamagePointScanResult point : points) {
                    String mode = point.constant() ? "替换（r）" : "乘数（m）";
                    lines.append("- `").append(point.attribute()).append("`  <!-- 模式：").append(mode)
                            .append("，默认 ").append(point.defaultDamage()).append("，伤害源 ")
                            .append(point.damageSource()).append("，")
                            .append(point.description()).append(" -->\n");
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
            case "living" -> "生物";
            case "projectile" -> "弹射物";
            case "entity" -> "实体";
            case "item" -> "物品";
            case "block" -> "方块";
            case "effect" -> "效果";
            case "behavior" -> "AI 行为";
            default -> "其他";
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
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return result.toString();
    }

    private static String escapeMarkdown(String value) {
        return value.replace("*", "\\*").replace("_", "\\_").replace("|", "\\|");
    }
}
