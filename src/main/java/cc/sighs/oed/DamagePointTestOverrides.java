package cc.sighs.oed;

import com.mojang.logging.LogUtils;
import fuzs.illagerinvasion.world.entity.monster.Invoker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = OneEnoughDamage.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DamagePointTestOverrides {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SMALL_FIREBALL_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "net/minecraft/world/entity/projectile/small_fireball/on_hit_entity/1"
    );
    private static final ResourceLocation SONIC_BOOM_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "net/minecraft/world/entity/ai/behavior/warden/sonic_boom/lambda_tick_2/1"
    );
    private static final ResourceLocation GUARDIAN_THORNS_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "net/minecraft/world/entity/monster/guardian/hurt/1"
    );
    private static final ResourceLocation EVOKER_FANGS_INDIRECT_MAGIC_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "net/minecraft/world/entity/projectile/evoker_fangs/deal_damage_to/2"
    );
    private static final ResourceLocation INVOKER_FANGS_INDIRECT_MAGIC_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "fuzs/illagerinvasion/world/entity/monster/invoker_fangs/damage/2"
    );
    private static final ResourceLocation INVOKER_AREA_MAGIC_DAMAGE = new ResourceLocation(
            OneEnoughDamage.MODID,
            "fuzs/illagerinvasion/world/entity/monster/invoker__area_damage_goal/buff/1"
    );
    private static final double TEST_DAMAGE = 200.0D;

    private DamagePointTestOverrides() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (event.getEntity().getType() == EntityType.BLAZE) {
            setTestDamage((Blaze) event.getEntity(), SMALL_FIREBALL_DAMAGE);
        } else if (event.getEntity().getType() == EntityType.EVOKER) {
            setTestDamage((Evoker) event.getEntity(), EVOKER_FANGS_INDIRECT_MAGIC_DAMAGE);
        } else if (event.getEntity().getType() == EntityType.GUARDIAN) {
            setTestDamage((Guardian) event.getEntity(), GUARDIAN_THORNS_DAMAGE);
        } else if (event.getEntity().getType() == EntityType.WARDEN) {
            setTestDamage((Warden) event.getEntity(), SONIC_BOOM_DAMAGE);
        } else if (event.getEntity().getType() == EntityType.SKELETON) {
            setTestDamage((Skeleton) event.getEntity(), new ResourceLocation(OneEnoughDamage.MODID, "projectile_base_damage"));
        } else if (event.getEntity() instanceof Invoker invoker) {
            setTestDamage(invoker, INVOKER_FANGS_INDIRECT_MAGIC_DAMAGE);
            setTestDamage(invoker, INVOKER_AREA_MAGIC_DAMAGE);
        }
    }

    private static void setTestDamage(Blaze entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(Warden entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(Guardian entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(Evoker entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(Invoker entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(Skeleton entity, ResourceLocation attributeId) {
        setTestDamage((net.minecraft.world.entity.LivingEntity) entity, attributeId);
    }

    private static void setTestDamage(net.minecraft.world.entity.LivingEntity entity, ResourceLocation attributeId) {
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attributeId);
        if (attribute == null) {
            LOGGER.info("OED test override could not find {}", attributeId);
            return;
        }

        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(TEST_DAMAGE);
            LOGGER.info("OED test override set {} to {} on {}", attributeId, TEST_DAMAGE, entity);
        } else {
            LOGGER.info("OED test override found {} but {} had no attribute instance", attributeId, entity);
        }
    }
}
