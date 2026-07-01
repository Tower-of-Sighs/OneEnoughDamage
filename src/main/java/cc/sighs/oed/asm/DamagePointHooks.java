package cc.sighs.oed.asm;

import cc.sighs.oed.OneEnoughDamage;
import cc.sighs.oed.DamagePointAttributes;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

public final class DamagePointHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final Map<String, List<DamagePointData.DamagePoint>> DAMAGE_POINTS_BY_CALLER = buildDamagePointIndex();
    private static final Map<String, List<Integer>> OBSERVED_CALL_SITES = new ConcurrentHashMap<>();
    private static final Map<Class<? extends LivingEntity>, List<EntityType<? extends LivingEntity>>> INFERRED_OWNER_TYPES = new ConcurrentHashMap<>();

    private DamagePointHooks() {
    }

    public static float modifyIncomingDamage(LivingEntity target, DamageSource source, float amount) {
        DamagePointData.DamagePoint point = findDamagePoint(source, amount);
        if (point == null) {
            return modifyProjectileBaseDamage(source, amount);
        }

        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        return getDamage(target, attacker, point, amount);
    }

    private static float modifyProjectileBaseDamage(DamageSource source, float amount) {
        Entity directEntity = source.getDirectEntity();
        if (!(directEntity instanceof Projectile)) {
            LOGGER.info("OED no damage point matched source {} amount {}", source.getMsgId(), amount);
            return amount;
        }

        LivingEntity owner = projectileOwner(source, directEntity);
        if (owner == null) {
            LOGGER.info("OED kept projectile base at {} because {} has no living owner", amount, directEntity);
            return amount;
        }

        AttributeInstance instance = owner.getAttribute(DamagePointAttributes.PROJECTILE_BASE_DAMAGE.get());
        if (instance == null) {
            LOGGER.info("OED kept projectile base at {} because {} has no projectile base attribute", amount, owner);
            return amount;
        }

        double value = instance.getValue();
        if (value < 0.0D) {
            LOGGER.info("OED kept projectile base at {} because {} projectile base is disabled", amount, owner);
            return amount;
        }

        LOGGER.info("OED projectile base changed {} damage from {} to {} using {}", directEntity, amount, value, owner);
        return (float) value;
    }

    private static LivingEntity projectileOwner(DamageSource source, Entity directEntity) {
        Entity owner = source.getEntity();
        if (owner instanceof LivingEntity living) {
            return living;
        }
        if (directEntity instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    public static float getDamage(Entity attacker, String attributePath, float fallback) {
        LivingEntity living = resolveAttributeHolder(attacker);
        if (living == null) {
            LOGGER.info("OED kept {} at {} because attacker {} has no living attribute holder", attributePath, fallback, attacker);
            return fallback;
        }

        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(OneEnoughDamage.MODID, attributePath));
        if (attribute == null) {
            LOGGER.info("OED kept {} at {} because attribute is not registered", attributePath, fallback);
            return fallback;
        }

        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) {
            LOGGER.info("OED kept {} at {} because {} has no attribute instance", attributePath, fallback, living);
            return fallback;
        }

        float value = (float) instance.getValue();
        float result = attributePath.endsWith("/m") ? fallback * value : value;
        LOGGER.info("OED changed {} from {} to {} using {}", attributePath, fallback, result, living);
        return result;
    }

    private static float getDamage(LivingEntity target, Entity attacker, DamagePointData.DamagePoint point, float fallback) {
        LivingEntity living = resolveAttributeHolder(attacker);
        if (living == null) {
            living = inferAttributeHolder(target, point);
        }
        if (living == null) {
            LOGGER.info("OED kept {} at {} because attacker {} has no living attribute holder", point.attributePath(), fallback, attacker);
            return fallback;
        }

        return getDamage(living, point.attributePath(), fallback);
    }

    private static DamagePointData.DamagePoint findDamagePoint(DamageSource source, float amount) {
        String damageSource = source.getMsgId();
        for (Caller caller : findDamageCallers()) {
            List<DamagePointData.DamagePoint> points = DAMAGE_POINTS_BY_CALLER.get(caller.key());
            if (points == null) {
                continue;
            }

            List<DamagePointData.DamagePoint> matches = new ArrayList<>();
            for (DamagePointData.DamagePoint point : points) {
                if (!damageSourceMatches(point.damageSource(), damageSource)) {
                    continue;
                }
                matches.add(point);
            }
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }

            return findByObservedCallSite(caller, matches);
        }
        return null;
    }

    private static boolean damageSourceMatches(String scannedSource, String runtimeSource) {
        return scannedSource.equals(runtimeSource) || camelToSnake(scannedSource).equals(runtimeSource);
    }

    private static String camelToSnake(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    private static DamagePointData.DamagePoint findByObservedCallSite(Caller caller, List<DamagePointData.DamagePoint> matches) {
        String key = caller.key();
        List<Integer> callSites = OBSERVED_CALL_SITES.computeIfAbsent(key, ignored -> new ArrayList<>());
        int callSiteIndex;
        synchronized (callSites) {
            if (!callSites.contains(caller.byteCodeIndex())) {
                callSites.add(caller.byteCodeIndex());
                callSites.sort(Integer::compareTo);
            }
            if (caller.byteCodeIndex() < 0 || callSites.size() < matches.size()) {
                return null;
            }
            callSiteIndex = callSites.indexOf(caller.byteCodeIndex());
        }

        matches.sort(Comparator.comparingInt(DamagePointData.DamagePoint::ordinal));
        return callSiteIndex >= 0 && callSiteIndex < matches.size() ? matches.get(callSiteIndex) : null;
    }

    private static List<Caller> findDamageCallers() {
        return STACK_WALKER.walk(frames -> {
            boolean[] seenLivingHurt = {false};
            return frames
                    .filter(frame -> {
                        if (seenLivingHurt[0]) {
                            return true;
                        }
                        if ("net.minecraft.world.entity.LivingEntity".equals(frame.getClassName()) && "hurt".equals(frame.getMethodName())) {
                            seenLivingHurt[0] = true;
                        }
                        return false;
                    })
                    .map(frame -> new Caller(frame.getClassName(), frame.getMethodName(), frame.getDescriptor(), frame.getByteCodeIndex()))
                    .toList();
        });
    }

    private static Map<String, List<DamagePointData.DamagePoint>> buildDamagePointIndex() {
        List<DamagePointData.DamagePoint> points = DamagePointData.points();
        Map<String, List<DamagePointData.DamagePoint>> index = new HashMap<>();
        for (DamagePointData.DamagePoint point : points) {
            index.computeIfAbsent(callerKey(point.owner(), point.method(), point.descriptor()), ignored -> new ArrayList<>()).add(point);
        }
        return Map.copyOf(index);
    }

    private static String callerKey(String owner, String method, String descriptor) {
        return owner + "#" + method + descriptor;
    }

    private static LivingEntity resolveAttributeHolder(Entity attacker) {
        if (attacker instanceof LivingEntity living) {
            return living;
        }
        if (attacker instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity owner) {
            return owner;
        }
        return null;
    }

    private static LivingEntity inferAttributeHolder(LivingEntity target, DamagePointData.DamagePoint point) {
        if (!DamagePointConfig.inferAttributeHolder()) {
            return null;
        }

        List<EntityType<? extends LivingEntity>> ownerTypes = inferredLivingOwnerTypes(point.owner(), target.level());
        if (ownerTypes.isEmpty()) {
            return null;
        }

        AABB bounds = target.getBoundingBox().inflate(DamagePointConfig.inferAttributeHolderSearchRadius());
        List<LivingEntity> candidates = target.level().getEntitiesOfClass(
                LivingEntity.class,
                bounds,
                entity -> entity != target && ownerTypes.contains(entity.getType())
        );
        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(target)))
                .orElse(null);
    }

    private static List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(String scanOwner, Level level) {
        String className = scanOwner;
        int innerClassMarker = scanOwner.indexOf('$');
        if (innerClassMarker >= 0) {
            className = scanOwner.substring(0, innerClassMarker);
        }

        try {
            Class<?> ownerClass = Class.forName(className);
            if (!LivingEntity.class.isAssignableFrom(ownerClass)) {
                return List.of();
            }

            return inferredLivingOwnerTypes(ownerClass.asSubclass(LivingEntity.class), level);
        } catch (ClassNotFoundException ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        return INFERRED_OWNER_TYPES.computeIfAbsent(ownerClass, key -> scanLivingOwnerTypes(key, level));
    }

    @SuppressWarnings("unchecked")
    private static List<EntityType<? extends LivingEntity>> scanLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        List<EntityType<? extends LivingEntity>> entityTypes = new ArrayList<>();
        for (EntityType<?> entityType : ForgeRegistries.ENTITY_TYPES.getValues()) {
            Entity created = createEntityForType(entityType, level);
            if (created instanceof LivingEntity && ownerClass.isInstance(created)) {
                entityTypes.add((EntityType<? extends LivingEntity>) entityType);
            }
        }
        if (!entityTypes.isEmpty()) {
            LOGGER.info("OED inferred {} owner types {}", ownerClass.getName(), entityTypes.stream().map(ForgeRegistries.ENTITY_TYPES::getKey).toList());
        }
        return List.copyOf(entityTypes);
    }

    private static Entity createEntityForType(EntityType<?> entityType, Level level) {
        try {
            return entityType.create(level);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record Caller(String owner, String method, String descriptor, int byteCodeIndex) {
        private String key() {
            return callerKey(owner, method, descriptor);
        }
    }
}
