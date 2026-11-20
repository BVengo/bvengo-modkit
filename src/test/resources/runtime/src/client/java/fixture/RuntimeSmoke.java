package fixture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

public final class RuntimeSmoke {
    private static boolean initialised;
    private static boolean completed;
    private static int titleTicks;

    public static void initialise() {
        initialised = true;
        System.out.println("MODKIT_CLIENT_ENTRYPOINT");
    }

    public static void tick() {
        Minecraft game = Minecraft.getInstance();
        if (completed || !(game.gui.screen() instanceof TitleScreen) || game.gui.overlay() != null) {
            return;
        }

        if (++titleTicks < 5) {
            return;
        }

        if (!initialised) {
            throw new IllegalStateException("The loader did not initialise the client entrypoint");
        }

        verifyResource("common.txt", "common resource");
        verifyResource("client.txt", "client resource");
        if (!Common.value().equals(ClientOnly.value())) {
            throw new IllegalStateException("Source roots selected different loader branches");
        }

        completed = true;
        System.out.println("MODKIT_TITLE_READY " + Common.value());
        game.stop();
    }

    private static void verifyResource(String name, String expected) {
        try (InputStream stream = RuntimeSmoke.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null || !new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim().equals(expected)) {
                throw new IllegalStateException("Missing or incorrect runtime resource " + name);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read runtime resource " + name, exception);
        }
    }
}
