package cc.sighs.oed.asm;

import cc.sighs.oed.DamagePointAttributes;
import cc.sighs.oed.OneEnoughDamage;
import cc.sighs.oed.runtime.AttributeHolderResolver;
import cc.sighs.oed.runtime.DamagePointFinder;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

public final class DamagePointHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static DamagePointFinder finder;
    private static final AttributeHolderResolver ATTRIBUTE_HOLDER_RESOLVER = new AttributeHolderResolver();

    private DamagePointHooks() {
    }

    public static float modifyIncomingDamage(LivingEntity target, DamageSource source, float amount) {
        DamagePointData.DamagePoint point = finder().find();
        if (point == null) {
            LOGGER.info("OED no damage point matched amount {}", amount);
            return amount;
        }

        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        return getDamage(target, attacker, point, amount);
    }

    public static float getDamage(Entity attacker, String attributePath, float fallback) {
        LivingEntity living = ATTRIBUTE_HOLDER_RESOLVER.resolve(attacker);
        if (living == null) {
            LOGGER.info("OED kept {} at {} because attacker {} has no living attribute holder", attributePath, fallback, attacker);
            return fallback;
        }

        return getDamage(living, attributePath, fallback);
    }

    private static float getDamage(LivingEntity living, String attributePath, float fallback) {
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

        float pointValue = (float) instance.getValue();
        float pointDamage = attributePath.endsWith("/m") ? fallback * pointValue : pointValue;
        float result = applyGlobalDamage(living, pointDamage);
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

    private static float applyGlobalDamage(LivingEntity living, float damage) {
        AttributeInstance instance = living.getAttribute(DamagePointAttributes.GLOBAL_DAMAGE.get());
        if (instance == null) {
            return damage;
        }
        return damage * (float) instance.getValue();
    }

    private static DamagePointFinder finder() {
        if (finder == null) {
            finder = new DamagePointFinder(DamagePointData.points());
        }
        return finder;
    }
}
