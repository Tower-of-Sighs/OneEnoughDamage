package cc.sighs.oed.mixin;

import cc.sighs.oed.asm.DamagePointHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDamagePointMixin {
    @ModifyVariable(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private float oneenoughdamage$modifyIncomingDamage(float amount, DamageSource source) {
        return DamagePointHooks.modifyIncomingDamage((LivingEntity) (Object) this, source, amount);
    }
}
