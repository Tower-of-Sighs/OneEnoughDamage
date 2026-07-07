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
        lines.append("# OneEnoughDamage 硬编码伤害点配置字典\n");
        lines.append("# 改等号右侧的数字即可修改对应 attribute 的初始值，重启游戏后生效。\n");
        lines.append("# /r 表示替换原伤害，/m 表示作为乘数参与计算。\n\n");
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
                Set<String> renderedAttributes = new LinkedHashSet<>();
                for (DamagePointScanResult point : points) {
                    String attribute = configKey(point.attribute(), key);
                    if (!renderedAttributes.add(attribute)) {
                        continue;
                    }
                    float value = configuredValues.getOrDefault(
                            attribute,
                            configuredValues.getOrDefault(point.attribute(), point.defaultDamage())
                    );
                    lines.append("# 模式：")
                            .append(point.constant() ? "替换（r）" : "乘数（m）")
                            .append("，默认 ")
                            .append(point.defaultDamage())
                            .append("，")
                            .append(point.description())
                            .append("\n");
                    lines.append('"').append(escapeTomlString(attribute)).append("\" = ")
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

            String updated = appendMissingEntries(Files.readString(outputFile, StandardCharsets.UTF_8), generated);
            if (updated == null) {
                LOGGER.info("OED dictionary: toml has all generated keys at {}", outputFile);
                return;
            }

            backupExistingFile(outputFile);
            Files.writeString(outputFile, updated, StandardCharsets.UTF_8);
            LOGGER.info("OED dictionary: incrementally updated toml at {}", outputFile);
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

    private static String appendMissingEntries(String existing, String generated) {
        Set<String> existingKeys = tomlKeys(existing);
        List<TomlEntry> generatedEntries = tomlEntries(generated);
        StringBuilder additions = new StringBuilder();
        Set<String> added = new LinkedHashSet<>();
        for (TomlEntry entry : generatedEntries) {
            if (existingKeys.contains(entry.key()) || !added.add(entry.key())) {
                continue;
            }
            if (additions.isEmpty()) {
                additions.append("\n\n# OneEnoughDamage incremental additions / 增量新增条目\n");
            } else {
                additions.append("\n");
            }
            additions.append(entry.block());
            if (!entry.block().endsWith("\n")) {
                additions.append("\n");
            }
        }
        if (additions.isEmpty()) {
            return null;
        }
        String separator = existing.endsWith("\n") ? "" : "\n";
        return existing + separator + additions;
    }

    private static Set<String> tomlKeys(String content) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            String key = tomlKey(line);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static List<TomlEntry> tomlEntries(String content) {
        List<TomlEntry> entries = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                pendingComments.add(line);
                continue;
            }

            String key = tomlKey(line);
            if (key != null) {
                StringBuilder block = new StringBuilder();
                for (String comment : pendingComments) {
                    block.append(comment).append("\n");
                }
                block.append(line).append("\n");
                entries.add(new TomlEntry(key, block.toString()));
            }

            pendingComments.clear();
        }
        return entries;
    }

    private static String tomlKey(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("\"")) {
            return null;
        }
        boolean escaped = false;
        for (int i = 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                int equals = trimmed.indexOf('=', i + 1);
                return equals < 0 ? null : trimmed.substring(1, i).replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        return null;
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

    private static String configKey(String attribute, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return attribute;
        }
        return attribute + "@" + key.entityId();
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

    private static void appendGlobalDamageConfig(StringBuilder lines, Map<String, Float> configuredValues) {
        String globalKey = "oneenoughdamage:" + DamagePointAttributes.GLOBAL_DAMAGE_ATTRIBUTE_PATH;
        float value = configuredValues.getOrDefault(globalKey, 1.0F);
        lines.append("# 全局伤害倍率：作用于所有挂靠伤害\n");
        lines.append("# Global damage multiplier: applies to all attributed damage\n");
        lines.append('"').append(escapeTomlString(globalKey)).append("\" = ")
                .append(formatFloat(value)).append("\n\n");
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

    private record TomlEntry(String key, String block) {
    }
}
