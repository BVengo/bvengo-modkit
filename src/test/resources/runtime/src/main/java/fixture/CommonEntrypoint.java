package fixture;

//? if neoforge {
/*import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod("fixture")
public final class CommonEntrypoint {
    public CommonEntrypoint() {
        NeoForge.EVENT_BUS.addListener(CommonEntrypoint::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!Common.value().equals("neoforge")) {
            throw new IllegalStateException("Incorrect common loader branch");
        }

        System.out.println("MODKIT_SERVER_READY neoforge");
        event.getServer().halt(false);
    }
}
*///?}
