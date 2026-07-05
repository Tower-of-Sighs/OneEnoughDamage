package cc.sighs.oed.dictionary;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        lines.append("# OneEnoughDamage 硬编码伤害点配置字典\n");
        lines.append("# 改等号右侧的数字即可修改对应 attribute 的初始值，重启游戏后生效。\n");
        lines.append("# /r 表示替换原伤害，/m 表示作为乘数参与计算。\n\n");
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
                        lines.append("（").append(key.zhName()).append("）");
                    }
                }
                String typeLabel = typeLabel(key.type());
                if (!typeLabel.isEmpty()) {
                    lines.append("（类型：").append(typeLabel).append("）");
                }
                lines.append("\n");
                appendAttackDamageConfig(lines, configuredValues, key);

                List<DamagePointScanResult> points = entry.getValue();
                points.sort(Comparator.comparing((DamagePointScanResult p) -> p.owner())
                        .thenComparing(DamagePointScanResult::method)
                        .thenComparingInt(DamagePointScanResult::ordinal));
                for (DamagePointScanResult point : points) {
                    String attribute = point.attribute();
                    float value = configuredValues.getOrDefault(attribute, point.defaultDamage());
                    lines.append("# 模式：").append(point.constant() ? "替换（r）" : "乘数（m）")
                            .append("，默认 ").append(point.defaultDamage())
                            .append("，伤害源 ").append(point.damageSource())
                            .append("，").append(point.description())
                            .append("\n");
                    lines.append('"').append(escapeTomlString(attribute)).append("\" = ")
                            .append(formatFloat(value)).append("\n");
                }
                lines.append("\n");
            }
        }

        try {
            Files.createDirectories(outputFile.getParent());
            if (isUnchanged(outputFile, lines.toString())) {
                LOGGER.info("OED dictionary: toml unchanged at {}", outputFile);
                return;
            }
            backupExistingFile(outputFile);
            try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(lines.toString());
            }
            LOGGER.info("OED dictionary: wrote toml to {}", outputFile);
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
            backup = outputFile.resolveSibling(baseName + ".backup-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + "-" + duplicate + suffix);
            duplicate++;
        }
        Files.copy(outputFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        LOGGER.info("OED dictionary: backed up existing toml to {}", backup);
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

    private static String escapeTomlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        lines.append("# 原版近战基础伤害：只作用于 ").append(key.entityId()).append("\n");
        lines.append("# Vanilla melee base damage: only applies to ").append(key.entityId()).append("\n");
        lines.append('"').append(escapeTomlString(configKey)).append("\" = ")
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
