package cc.sighs.oed.mixin;

import cc.sighs.oed.OneEnoughDamage;
import cc.sighs.oed.asm.DamagePointConfig;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Attribute.class)
public abstract class AttributeDefaultValueMixin {
    @Inject(method = "getDefaultValue", at = @At("HEAD"), cancellable = true)
    private void oneenoughdamage$getConfiguredDefaultValue(CallbackInfoReturnable<Double> cir) {
        if (!DamagePointConfig.debugMode()) {
            return;
        }

        ResourceLocation id = BuiltInRegistries.ATTRIBUTE.getKey((Attribute) (Object) this);
        if (id == null || !OneEnoughDamage.MODID.equals(id.getNamespace())) {
            return;
        }

        Float configured = DamagePointTomlConfig.configuredValue(id.toString());
        if (configured != null && Float.isFinite(configured)) {
            cir.setReturnValue((double) configured);
        }
    }
}
