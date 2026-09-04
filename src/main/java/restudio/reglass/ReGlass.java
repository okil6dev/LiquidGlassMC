package restudio.reglass;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restudio.reglass.client.ReGlassClient;

@Mod(ReGlass.MOD_ID)
public class ReGlass {
    public static final String MOD_ID = "liquidglassmc";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public ReGlass(IEventBus modEventBus, ModContainer container) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ReGlassClient.init(modEventBus);
        }
    }
}