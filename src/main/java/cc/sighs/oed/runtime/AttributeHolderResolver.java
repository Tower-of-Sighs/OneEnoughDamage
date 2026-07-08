package cc.sighs.oed.runtime;

import cc.sighs.oed.asm.DamagePointConfig;
import cc.sighs.oed.asm.DamagePointData;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

public final class AttributeHolderResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<Class<? extends LivingEntity>, List<EntityType<? extends LivingEntity>>> inferredOwnerTypes = new ConcurrentHashMap<>();

    public LivingEntity resolve(Entity attacker) {
        if (attacker instanceof LivingEntity living) {
            return living;
        }
        if (attacker instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity owner) {
            return owner;
        }
        return null;
    }

    public LivingEntity infer(LivingEntity target, DamagePointData.DamagePoint point) {
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

    private List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(String scanOwner, Level level) {
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
    private List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        return inferredOwnerTypes.computeIfAbsent(ownerClass, key -> scanLivingOwnerTypes(key, level));
    }

    @SuppressWarnings("unchecked")
    private List<EntityType<? extends LivingEntity>> scanLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        List<EntityType<? extends LivingEntity>> entityTypes = new ArrayList<>();
        for (EntityType<?> entityType : ForgeRegistries.ENTITY_TYPES.getValues()) {
            Entity created = createEntityForType(entityType, level);
            if (created instanceof LivingEntity && ownerClass.isInstance(created)) {
                entityTypes.add((EntityType<? extends LivingEntity>) entityType);
            }
        }
        if (!entityTypes.isEmpty() && DamagePointConfig.debugMode()) {
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
}
