package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointData;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = OneEnoughDamage.MODID)
public final class DamagePointAttributes {
    public static final String GLOBAL_DAMAGE_ATTRIBUTE_PATH = "global_damage";
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(BuiltInRegistries.ATTRIBUTE, OneEnoughDamage.MODID);
    @SuppressWarnings("unused")
    private static final DeferredHolder<Attribute, Attribute> LEGACY_PROJECTILE_BASE_DAMAGE = ATTRIBUTES.register(
            "projectile_base_damage",
            () -> new RangedAttribute(
                    "oneenoughdamage.projectile_base_damage",
                    -1.0D,
                    -1.0D,
                    2048.0D
            ).setSyncable(true)
    );
    public static final DeferredHolder<Attribute, Attribute> GLOBAL_DAMAGE = ATTRIBUTES.register(
            GLOBAL_DAMAGE_ATTRIBUTE_PATH,
            () -> new RangedAttribute(
                    "oneenoughdamage.global_damage",
                    1.0D,
                    0.0D,
                    2048.0D
            ).setSyncable(true)
    );
    private static final Map<DamagePointData.DamagePoint, DeferredHolder<Attribute, Attribute>> DAMAGE_POINT_ATTRIBUTES =
            registerDamagePointAttributes();
    private static final Map<String, Double> CONFIGURED_DEFAULTS = configuredDefaults();

    static {
        DamagePointTomlConfig.addChangeListener(RuntimeSync::syncConfiguredAttributes);
    }

    private DamagePointAttributes() {
    }

    private static Map<DamagePointData.DamagePoint, DeferredHolder<Attribute, Attribute>> registerDamagePointAttributes() {
        Map<DamagePointData.DamagePoint, DeferredHolder<Attribute, Attribute>> attributes = new LinkedHashMap<>();
        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            DeferredHolder<Attribute, Attribute> attribute = ATTRIBUTES.register(
                    point.attributePath(),
                    () -> new RangedAttribute(
                            point.description(),
                            point.defaultDamage(),
                            0.0D,
                            2048.0D
                    ).setSyncable(true)
            );
            attributes.put(point, attribute);
        }
        return attributes;
    }

    private static Map<String, Double> configuredDefaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(OneEnoughDamage.MODID + ":" + GLOBAL_DAMAGE_ATTRIBUTE_PATH, 1.0D);
        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            defaults.put(OneEnoughDamage.MODID + ":" + point.attributePath(), (double) point.defaultDamage());
        }
        return Map.copyOf(defaults);
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> entityType : event.getTypes()) {
            if (!event.has(entityType, GLOBAL_DAMAGE)) {
                event.add(entityType, GLOBAL_DAMAGE);
            }
            for (DeferredHolder<Attribute, Attribute> attribute : DAMAGE_POINT_ATTRIBUTES.values()) {
                if (!event.has(entityType, attribute)) {
                    event.add(entityType, attribute);
                }
            }
        }
    }

    @EventBusSubscriber(modid = OneEnoughDamage.MODID)
    public static final class RuntimeSync {
        private static volatile MinecraftServer server;

        private RuntimeSync() {
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            server = event.getServer();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            server = null;
        }

        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity living)) {
                return;
            }

            Set<String> attributeIds = new LinkedHashSet<>(CONFIGURED_DEFAULTS.keySet());
            attributeIds.addAll(DamagePointTomlConfig.configuredKeys());
            syncEntity(living, attributeIds);
        }

        private static void syncConfiguredAttributes(Collection<String> attributeIds) {
            MinecraftServer current = server;
            if (current == null) {
                return;
            }

            for (ServerLevel level : current.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof LivingEntity living) {
                        syncEntity(living, attributeIds);
                    }
                }
            }
        }

        private static void syncEntity(LivingEntity living, Collection<String> attributeIds) {
            for (String attributeId : attributeIds.stream()
                    .sorted(Comparator.comparingInt(id -> id.contains("@") ? 1 : 0))
                    .toList()) {
                ConfiguredAttributeKey key = ConfiguredAttributeKey.parse(attributeId);
                if (key == null || !key.matches(living)) {
                    continue;
                }

                ResourceLocation id = ResourceLocation.tryParse(key.attributeId());
                if (id == null) {
                    continue;
                }
                Double fallback = CONFIGURED_DEFAULTS.get(key.attributeId());
                Float configured = DamagePointTomlConfig.configuredValue(attributeId);
                if (fallback == null) {
                    if (configured == null) {
                        continue;
                    }
                    fallback = (double) configured;
                }

                Holder.Reference<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null);
                if (attribute == null) {
                    continue;
                }

                AttributeInstance instance = living.getAttribute(attribute);
                if (instance == null) {
                    continue;
                }

                double value = configured == null ? fallback : configured;
                if (Double.compare(instance.getBaseValue(), value) != 0) {
                    instance.setBaseValue(value);
                }
            }
        }

        private record ConfiguredAttributeKey(String attributeId, String entityId) {
            private static ConfiguredAttributeKey parse(String value) {
                int entitySeparator = value.indexOf('@');
                if (entitySeparator < 0) {
                    return new ConfiguredAttributeKey(value, null);
                }
                String attributeId = value.substring(0, entitySeparator);
                String entityId = value.substring(entitySeparator + 1);
                if (attributeId.isBlank() || entityId.isBlank()) {
                    return null;
                }
                return new ConfiguredAttributeKey(attributeId, entityId);
            }

            private boolean matches(LivingEntity living) {
                if (entityId == null) {
                    return true;
                }
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
                return typeId != null && entityId.equals(typeId.toString());
            }
        }
    }
}
