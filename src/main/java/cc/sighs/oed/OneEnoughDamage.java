package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointDictionaryGenerator;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(OneEnoughDamage.MODID)
public class OneEnoughDamage {
    public static final String MODID = "oneenoughdamage";
    private static final Logger LOGGER = LogUtils.getLogger();

    public OneEnoughDamage(IEventBus modEventBus) {
        DamagePointAttributes.ATTRIBUTES.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DamagePointDictionaryGenerator.generateIfNeeded();
            DamagePointTomlConfig.startWatcherIfNeeded();
        });
    }
}
