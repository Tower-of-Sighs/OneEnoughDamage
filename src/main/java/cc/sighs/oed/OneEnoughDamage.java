package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointDictionaryGenerator;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(OneEnoughDamage.MODID)
public class OneEnoughDamage {
    public static final String MODID = "oneenoughdamage";
    private static final Logger LOGGER = LogUtils.getLogger();

    public OneEnoughDamage() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        DamagePointAttributes.ATTRIBUTES.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(DamagePointDictionaryGenerator::generateIfNeeded);
    }
}
