package dev.lanhuo.anvilautotree.client;

import dev.anvilcraft.lib.v2.ui.flexible.ButtonRenderer;
import dev.anvilcraft.lib.v2.ui.flexible.FlyoutRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.Rect2i;

/** Keeps renderer scopes and their geometry for one crafting window. */
final class AnvilLibWindow {
    private final ButtonRenderer.Scope window = new FlyoutRenderer().shaded1px().open();
    private final Button[] buttons;

    AnvilLibWindow() {
        this(3);
    }

    AnvilLibWindow(int buttonCount) {
        buttons = new Button[buttonCount];
        java.util.Arrays.setAll(buttons, index -> new Button());
    }

    void layout(int x, int y, int width, int height, Rect2i confirm, Rect2i filter, Rect2i cancel) {
        layoutFrame(x, y, width, height);
        buttons[0].layout(confirm);
        buttons[1].layout(filter);
        buttons[2].layout(cancel);
    }

    void layoutFrame(int x, int y, int width, int height) {
        window.pos(x, y).size(width, height);
    }

    void layoutButton(int index, Rect2i bounds) {
        buttons[index].layout(bounds);
    }

    void render(GuiGraphicsExtractor graphics) {
        draw(window, graphics);
    }

    void renderButton(int index, GuiGraphicsExtractor graphics, boolean enabled, boolean hovered, boolean selected) {
        Button button = buttons[index];
        draw(!enabled ? button.disabled : selected ? button.selected : hovered ? button.hovered : button.normal, graphics);
    }

    private static void draw(ButtonRenderer.Scope scope, GuiGraphicsExtractor graphics) {
        try {
            scope.graphics(graphics).render();
        } finally {
            // Closing a scope also clears its cached position and size.
            scope.graphics(null);
        }
    }

    private static final class Button {
        private final ButtonRenderer.Scope normal = new ButtonRenderer().shaded2px().open();
        private final ButtonRenderer.Scope hovered = new ButtonRenderer().shaded2px().highlighted().open();
        private final ButtonRenderer.Scope selected = new ButtonRenderer().shaded2px().pressed().open();
        private final ButtonRenderer.Scope disabled = new ButtonRenderer().shaded2px().alpha(0.65F).open();

        void layout(Rect2i bounds) {
            position(normal, bounds);
            position(hovered, bounds);
            position(selected, bounds);
            position(disabled, bounds);
        }

        private static void position(ButtonRenderer.Scope scope, Rect2i bounds) {
            scope.pos(bounds.getX(), bounds.getY()).size(bounds.getWidth(), bounds.getHeight());
        }
    }
}
