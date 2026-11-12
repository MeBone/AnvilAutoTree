package dev.lanhuo.anvilautocraft.client;

import dev.anvilcraft.lib.v2.ui.flexible.ButtonRenderer;
import dev.anvilcraft.lib.v2.ui.flexible.FlyoutRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

/** Shared chrome for the live crafting tree and its layout preview. */
final class AnvilLibWindow {
    private static final FlyoutRenderer WINDOW = new FlyoutRenderer();
    private static final ButtonRenderer BUTTON = new ButtonRenderer();
    private static final ButtonRenderer HOVERED = new ButtonRenderer();
    private static final ButtonRenderer SELECTED = new ButtonRenderer();
    private static final ButtonRenderer DISABLED = new ButtonRenderer();

    static {
        WINDOW.shaded1px();
        HOVERED.highlighted();
        SELECTED.pressed();
        DISABLED.alpha(0.65F);
    }

    private AnvilLibWindow() {
    }

    static void render(GuiGraphics graphics, int x, int y, int width, int height) {
        try (var scope = WINDOW.open()) {
            scope.graphics(graphics).pos(x, y).size(width, height).render();
        }
    }

    static void renderButton(GuiGraphics graphics, Rect2i bounds, boolean enabled, boolean hovered, boolean selected) {
        ButtonRenderer renderer = !enabled ? DISABLED : selected ? SELECTED : hovered ? HOVERED : BUTTON;
        try (var scope = renderer.open()) {
            scope.graphics(graphics).pos(bounds.getX(), bounds.getY())
                .size(bounds.getWidth(), bounds.getHeight()).render();
        }
    }
}
