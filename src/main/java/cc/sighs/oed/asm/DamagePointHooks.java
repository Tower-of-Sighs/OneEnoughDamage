package cc.sighs.oed.asm;

import cc.sighs.oed.DamagePointAttributes;
import cc.sighs.oed.OneEnoughDamage;
import cc.sighs.oed.runtime.AttributeHolderResolver;
import cc.sighs.oed.runtime.DamagePointFinder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.projectile.Projectile;
import org.slf4j.Logger;

public final class DamagePointHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static DamagePointFinder finder;
    private static final AttributeHolderResolver ATTRIBUTE_HOLDER_RESOLVER = new AttributeHolderResolver();

    private DamagePointHooks() {
    }

    public static float modifyIncomingDamage(LivingEntity target, DamageSource source, float amount) {
        DamagePointData.DamagePoint point = finder().find(source.getMsgId(), amount);
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

        LivingEntity owner = ATTRIBUTE_HOLDER_RESOLVER.resolve(source.getEntity() != null ? source.getEntity() : directEntity);
        if (owner == null) {
            LOGGER.info("OED kept projectile base at {} because {} has no living owner", amount, directEntity);
            return amount;
        }

        AttributeInstance instance = owner.getAttribute(DamagePointAttributes.PROJECTILE_BASE_DAMAGE);
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

    public static float getDamage(Entity attacker, String attributePath, float fallback) {
        LivingEntity living = ATTRIBUTE_HOLDER_RESOLVER.resolve(attacker);
        if (living == null) {
            LOGGER.info("OED kept {} at {} because attacker {} has no living attribute holder", attributePath, fallback, attacker);
            return fallback;
        }

        Holder.Reference<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(OneEnoughDamage.id(attributePath)).orElse(null);
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
        LivingEntity living = ATTRIBUTE_HOLDER_RESOLVER.resolve(attacker);
        if (living == null) {
            living = ATTRIBUTE_HOLDER_RESOLVER.infer(target, point);
        }
        if (living == null) {
            LOGGER.info("OED kept {} at {} because attacker {} has no living attribute holder", point.attributePath(), fallback, attacker);
            return fallback;
        }

        return getDamage(living, point.attributePath(), fallback);
    }

    private static DamagePointFinder finder() {
        if (finder == null) {
            finder = new DamagePointFinder(DamagePointData.points());
        }
        return finder;
    }
}
