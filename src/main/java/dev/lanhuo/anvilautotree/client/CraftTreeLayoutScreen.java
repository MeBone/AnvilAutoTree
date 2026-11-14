package dev.lanhuo.anvilautotree.client;

import dev.lanhuo.anvilautotree.net.CraftNodePayload;
import dev.lanhuo.anvilautotree.net.StorageKind;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

final class CraftTreeLayoutScreen extends Screen {
    private static final Identifier TERMINAL = Identifier.fromNamespaceAndPath(
        "anvilcraft", "textures/gui/misc/background/storage_station.png"
    );
    private static final int TERMINAL_WIDTH = 300;
    private static final int TERMINAL_HEIGHT = 222;
    private static final int TOOLBAR_HEIGHT = 30;
    private final Screen parent;
    private final CraftTreeOverlay preview = new CraftTreeOverlay(true);
    private Button reset;
    private Button cancel;
    private Button save;

    CraftTreeLayoutScreen(Screen parent) {
        super(Component.translatable("anvilautotree.layout.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!preview.isVisible()) {
            preview.show(List.of(
                new CraftNodePayload(-1, Items.PISTON.getDefaultInstance(), 16, 0, 0, false),
                new CraftNodePayload(0, Items.OAK_PLANKS.getDefaultInstance(), 48, 0, 12, false),
                new CraftNodePayload(0, Items.COBBLESTONE.getDefaultInstance(), 64, 64, 0, false),
                new CraftNodePayload(0, Items.IRON_INGOT.getDefaultInstance(), 16, 16, 0, false),
                new CraftNodePayload(0, Items.REDSTONE.getDefaultInstance(), 16, 16, 0, false),
                new CraftNodePayload(1, Items.OAK_LOG.getDefaultInstance(), 12, 16, 0, false)
            ), null, false, StorageKind.HYPERDIMENSION, 16, ignored -> {}, ignored -> {}, () -> {});
        }
        int buttonWidth = Math.min(96, (width - 24) / 3);
        reset = addRenderableWidget(Button.builder(Component.translatable("anvilautotree.layout.reset"),
            button -> preview.setBounds(AutoCraftClientConfig.defaultLayout())).width(buttonWidth).build());
        cancel = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(buttonWidth).build());
        save = addRenderableWidget(Button.builder(Component.translatable("anvilautotree.layout.save"),
            button -> saveLayout()).width(buttonWidth).build());
        positionToolbar();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF272831);
        if (minecraft.getResourceManager().getResource(TERMINAL).isPresent()) {
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TERMINAL, (width - TERMINAL_WIDTH) / 2, (height - TERMINAL_HEIGHT) / 2,
                0, 0, TERMINAL_WIDTH, TERMINAL_HEIGHT, 512, 256);
        }
        preview.render(graphics, mouseX, mouseY);
        int toolbarY = positionToolbar();
        graphics.nextStratum();
        graphics.fill(0, toolbarY, width, toolbarY + TOOLBAR_HEIGHT, 0xEE20212A);
        int hintY = toolbarY == 0 ? height - 12 : 4;
        var window = preview.extraAreas().getFirst();
        if (hintY + font.lineHeight <= window.getY() || hintY >= window.getY() + window.getHeight()) {
            graphics.centeredText(font, Component.translatable("anvilautotree.layout.hint"), width / 2, hintY, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The terminal preview is already the background for this screen.
    }

    private int positionToolbar() {
        var window = preview.extraAreas().getFirst();
        int bottom = window.getY() + window.getHeight();
        int y = bottom <= height - TOOLBAR_HEIGHT || height - bottom >= window.getY() ? height - TOOLBAR_HEIGHT : 0;
        int width = save.getWidth();
        int left = (this.width - width * 3 - 12) / 2;
        reset.setPosition(left, y + 5);
        cancel.setPosition(left + width + 6, y + 5);
        save.setPosition(left + (width + 6) * 2, y + 5);
        return y;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick) || preview.mousePressed(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
        return preview.mouseDragged(event.x(), event.y(), event.button()) || super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        return preview.mouseReleased(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            saveLayout();
            return true;
        }
        return super.keyPressed(event);
    }

    private void saveLayout() {
        AutoCraftClientConfig.saveLayout(preview.requestedBounds());
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
