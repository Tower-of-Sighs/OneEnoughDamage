package cc.sighs.oed.dictionary;

import cc.sighs.oed.DamagePointAttributes;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import cc.sighs.oed.scan.DamagePointScanResult;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

public final class TomlDictionaryRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TomlDictionaryRenderer() {
    }

    public static void render(Map<String, Map<MobKey, List<DamagePointScanResult>>> groups, Path outputFile) {
        Map<String, Float> configuredValues = DamagePointTomlConfig.readValues(outputFile);
        StringBuilder lines = new StringBuilder();
        lines.append("# OneEnoughDamage hardcoded damage config dictionary\n");
        lines.append("# Edit the number on the right to change the initial value of the attribute.\n");
        lines.append("# /r means replace original damage, /m means multiply original damage.\n\n");
        appendGlobalDamageConfig(lines, configuredValues);

        for (Map.Entry<String, Map<MobKey, List<DamagePointScanResult>>> namespaceEntry : groups.entrySet()) {
            Map<MobKey, List<DamagePointScanResult>> mobs = namespaceEntry.getValue();
            if (mobs == null || mobs.isEmpty()) {
                continue;
            }

            for (Map.Entry<MobKey, List<DamagePointScanResult>> entry : mobs.entrySet()) {
                MobKey key = entry.getKey();
                lines.append("# ").append(key.fallback());
                if (!key.enName().isBlank() || !key.zhName().isBlank()) {
                    lines.append(" - ").append(key.enName());
                    if (!key.zhName().equals(key.enName())) {
                        lines.append(" (").append(key.zhName()).append(")");
                    }
                }
                String typeLabel = typeLabel(key.type());
                if (!typeLabel.isEmpty()) {
                    lines.append(" (Type: ").append(typeLabel).append(")");
                }
                lines.append("\n");
                appendEntitySection(lines, key);
                appendAttackDamageConfig(lines, configuredValues, key);
                appendGlobalDamageConfig(lines, configuredValues, key);

                List<DamagePointScanResult> points = entry.getValue();
                points.sort(Comparator.comparing(DamagePointScanResult::owner)
                        .thenComparing(DamagePointScanResult::method)
                        .thenComparingInt(DamagePointScanResult::ordinal));
                Set<String> renderedAttributes = new LinkedHashSet<>();
                for (DamagePointScanResult point : points) {
                    String internalAttribute = scopedConfigKey(point.attribute(), key);
                    String outputAttribute = outputConfigKey(point.attribute(), key);
                    if (!renderedAttributes.add(outputAttribute)) {
                        continue;
                    }
                    float value = configuredValues.getOrDefault(
                            internalAttribute,
                            configuredValues.getOrDefault(point.attribute(), point.defaultDamage())
                    );
                    lines.append("# mode: ")
                            .append(point.constant() ? "replace (/r)" : "multiply (/m)")
                            .append(", default: ").append(point.defaultDamage())
                            .append(", DamageType: ").append(point.damageType())
                            .append(", ").append(point.description())
                            .append("\n");
                    lines.append('"').append(escapeTomlString(outputAttribute)).append("\" = ")
                            .append(formatFloat(value)).append("\n");
                }
                lines.append("\n");
            }
        }

        try {
            Files.createDirectories(outputFile.getParent());
            String generated = lines.toString();
            if (isUnchanged(outputFile, generated)) {
                LOGGER.info("OED dictionary: toml unchanged at {}", outputFile);
                return;
            }

            if (!Files.isRegularFile(outputFile)) {
                try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                    writer.write(generated);
                }
                LOGGER.info("OED dictionary: wrote toml to {}", outputFile);
                return;
            }

            backupExistingFile(outputFile);
            Files.writeString(outputFile, generated, StandardCharsets.UTF_8);
            LOGGER.info("OED dictionary: rewrote toml at {}", outputFile);
        } catch (IOException e) {
            LOGGER.error("OED dictionary: failed to write toml", e);
        }
    }

    private static boolean isUnchanged(Path outputFile, String content) throws IOException {
        return Files.isRegularFile(outputFile) && Files.readString(outputFile, StandardCharsets.UTF_8).equals(content);
    }

    private static void backupExistingFile(Path outputFile) throws IOException {
        if (!Files.isRegularFile(outputFile)) {
            return;
        }

        String fileName = outputFile.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension >= 0 ? fileName.substring(0, extension) : fileName;
        String suffix = extension >= 0 ? fileName.substring(extension) : "";
        Path backup = outputFile.resolveSibling(baseName + ".backup-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + suffix);
        int duplicate = 1;
        while (Files.exists(backup)) {
            backup = outputFile.resolveSibling(
                    baseName + ".backup-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + "-" + duplicate + suffix
            );
            duplicate++;
        }
        Files.copy(outputFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        LOGGER.info("OED dictionary: backed up existing toml to {}", backup);
        pruneBackups(outputFile, baseName, suffix);
    }

    private static void pruneBackups(Path outputFile, String baseName, String suffix) throws IOException {
        Path parent = outputFile.getParent();
        if (parent == null) {
            return;
        }
        String prefix = baseName + ".backup-";
        List<Path> backups = new ArrayList<>();
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(suffix);
                    })
                    .forEach(backups::add);
        }
        backups.sort(Comparator.comparing((Path path) -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());
        for (int i = 5; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
            LOGGER.info("OED dictionary: removed old toml backup {}", backups.get(i));
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

    private static String escapeTomlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String scopedConfigKey(String attribute, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return attribute;
        }
        return attribute + "@" + key.entityId();
    }

    private static String outputConfigKey(String attribute, MobKey key) {
        return attribute;
    }

    private static void appendEntitySection(StringBuilder lines, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return;
        }

        lines.append("[entity.\"").append(escapeTomlString(key.entityId())).append("\"]\n");
    }

    private static void appendAttackDamageConfig(StringBuilder lines, Map<String, Float> configuredValues, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return;
        }

        String configKey = "minecraft:generic.attack_damage@" + key.entityId();
        Float defaultValue = attackDamageDefault(key.entityId());
        if (defaultValue == null) {
            return;
        }
        float value = configuredValues.getOrDefault(configKey, defaultValue);
        lines.append("# Vanilla melee base damage: only applies to ").append(key.entityId()).append("\n");
        lines.append('"').append(escapeTomlString(outputConfigKey("minecraft:generic.attack_damage", key))).append("\" = ")
                .append(formatFloat(value)).append("\n");
    }

    private static void appendGlobalDamageConfig(StringBuilder lines, Map<String, Float> configuredValues) {
        String globalKey = "oneenoughdamage:" + DamagePointAttributes.GLOBAL_DAMAGE_ATTRIBUTE_PATH;
        float value = configuredValues.getOrDefault(globalKey, 1.0F);
        lines.append("# Global damage multiplier: applies to all attributed damage\n");
        lines.append('"').append(escapeTomlString(globalKey)).append("\" = ")
                .append(formatFloat(value)).append("\n\n");
    }

    private static void appendGlobalDamageConfig(StringBuilder lines, Map<String, Float> configuredValues, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return;
        }

        String globalKey = "oneenoughdamage:" + DamagePointAttributes.GLOBAL_DAMAGE_ATTRIBUTE_PATH;
        String configKey = globalKey + "@" + key.entityId();
        float value = configuredValues.getOrDefault(configKey, configuredValues.getOrDefault(globalKey, 1.0F));
        lines.append("# OED global damage multiplier: only applies to ").append(key.entityId()).append("\n");
        lines.append('"').append(escapeTomlString(outputConfigKey(globalKey, key))).append("\" = ")
                .append(formatFloat(value)).append("\n");
    }

    private static Float attackDamageDefault(String entityId) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            return null;
        }
        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (entityType == null || !DefaultAttributes.hasSupplier(entityType)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) entityType;
        if (!DefaultAttributes.getSupplier(livingType).hasAttribute(Attributes.ATTACK_DAMAGE)) {
            return null;
        }
        return (float) DefaultAttributes.getSupplier(livingType).getBaseValue(Attributes.ATTACK_DAMAGE);
    }

    private static String formatFloat(float value) {
        if (Float.isFinite(value) && value == (long) value) {
            return Long.toString((long) value) + ".0";
        }
        return Float.toString(value);
    }
}
