package dev.lanhuo.anvilautotree.client;

import dev.lanhuo.anvilautotree.AnvilAutoTree;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端事件：tick 驱动，以及合成树侧边栏的绘制与输入拦截。
 *
 * <p>重放 JEI 转移需要逐 tick 轮询，因为存储快照是异步分页拉取的。</p>
 *
 * <p>侧边栏不再替换当前界面，而是挂在 {@link ScreenEvent.Render.Post} 上叠加绘制；输入事件在
 * {@code Pre} 阶段判断命中面板后取消，避免点击穿透到 JEI 配方界面或终端容器界面。</p>
 */
@EventBusSubscriber(modid = AnvilAutoTree.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AutoCraftClientEvents {
    private AutoCraftClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AutoCraftClient.tick();
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        CraftTreeOverlay.LIVE.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        AutoCraftClient.onScreenOpening(event.getNewScreen());
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (CraftTreeOverlay.LIVE.quantityKeyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        if (event.getKeyCode() != GLFW.GLFW_KEY_SPACE) {
            return;
        }
        GuiEventListener focus = event.getScreen().getFocused();
        while (focus instanceof ContainerEventHandler container) {
            focus = container.getFocused();
        }
        if (!(focus instanceof EditBox) && CraftTreeOverlay.LIVE.confirmFromKey()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (CraftTreeOverlay.LIVE.quantityCharTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (CraftTreeOverlay.LIVE.mousePressed(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.getScreen().setFocused(null);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (CraftTreeOverlay.LIVE.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (CraftTreeOverlay.LIVE.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        boolean horizontal = Math.abs(event.getScrollDeltaX()) > Math.abs(event.getScrollDeltaY());
        double delta = horizontal ? event.getScrollDeltaX() : event.getScrollDeltaY();
        if (CraftTreeOverlay.LIVE.mouseScrolled(event.getMouseX(), event.getMouseY(), delta, horizontal)) {
            event.setCanceled(true);
        }
    }
}
