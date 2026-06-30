package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = OneEnoughDamage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DamagePointAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, OneEnoughDamage.MODID);
    public static final RegistryObject<Attribute> PROJECTILE_BASE_DAMAGE = ATTRIBUTES.register(
            "projectile_base_damage",
            () -> new RangedAttribute(
                    "oneenoughdamage.projectile_base_damage",
                    -1.0D,
                    -1.0D,
                    2048.0D
            ).setSyncable(true)
    );

    private static final Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> DAMAGE_POINT_ATTRIBUTES = registerDamagePointAttributes();

    private DamagePointAttributes() {
    }

    private static Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> registerDamagePointAttributes() {
        Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> attributes = new LinkedHashMap<>();
        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            RegistryObject<Attribute> attribute = ATTRIBUTES.register(
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

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> entityType : event.getTypes()) {
            if (!event.has(entityType, PROJECTILE_BASE_DAMAGE.get())) {
                event.add(entityType, PROJECTILE_BASE_DAMAGE.get());
            }
            for (RegistryObject<Attribute> attribute : DAMAGE_POINT_ATTRIBUTES.values()) {
                if (!event.has(entityType, attribute.get())) {
                    event.add(entityType, attribute.get());
                }
            }
        }
    }
}
