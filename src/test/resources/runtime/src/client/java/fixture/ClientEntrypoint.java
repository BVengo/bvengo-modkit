package fixture;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;

public final class ClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RuntimeSmoke.initialise();
    }
}
//?} else {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "fixture", dist = Dist.CLIENT)
public final class ClientEntrypoint {
    public ClientEntrypoint() {
        RuntimeSmoke.initialise();
    }
}
*///?}
