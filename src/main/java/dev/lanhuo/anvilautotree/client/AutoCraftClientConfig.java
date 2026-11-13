package dev.lanhuo.anvilautotree.client;

import dev.lanhuo.anvilautotree.AnvilAutoTree;
import net.minecraft.client.renderer.Rect2i;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;

@Mod(value = AnvilAutoTree.MOD_ID, dist = Dist.CLIENT)
public final class AutoCraftClientConfig {
    static final int MIN_WIDTH = 176;
    static final int MIN_HEIGHT = 132;
    static final int AUTO_POSITION = -1;
    static final ModConfigSpec.IntValue WIDTH;
    static final ModConfigSpec.IntValue HEIGHT;
    static final ModConfigSpec.IntValue X;
    static final ModConfigSpec.IntValue Y;
    private static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("craftTree");
        WIDTH = builder.comment("Default window width in GUI pixels.").defineInRange("width", 224, MIN_WIDTH, 4096);
        HEIGHT = builder.comment("Default window height in GUI pixels.").defineInRange("height", 176, MIN_HEIGHT, 4096);
        X = builder.comment("Distance from the left edge in GUI pixels; -1 aligns to the right.")
            .defineInRange("x", AUTO_POSITION, AUTO_POSITION, 4096);
        Y = builder.comment("Distance from the top edge in GUI pixels; -1 centers vertically.")
            .defineInRange("y", AUTO_POSITION, AUTO_POSITION, 4096);
        builder.pop();
        SPEC = builder.build();
    }

    public AutoCraftClientConfig(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) ->
            new ConfigurationScreen(mod, parent, (root, type, config, title) -> type == ModConfig.Type.CLIENT
                ? new CraftTreeLayoutScreen(root)
                : new ConfigurationScreen.ConfigurationSectionScreen(root, type, config, title)));
    }

    static Rect2i currentLayout() {
        return new Rect2i(X.get(), Y.get(), WIDTH.get(), HEIGHT.get());
    }

    static Rect2i defaultLayout() {
        return new Rect2i(X.getDefault(), Y.getDefault(), WIDTH.getDefault(), HEIGHT.getDefault());
    }

    static void saveLayout(Rect2i bounds) {
        X.set(bounds.getX());
        Y.set(bounds.getY());
        WIDTH.set(bounds.getWidth());
        HEIGHT.set(bounds.getHeight());
        SPEC.save();
    }
}
