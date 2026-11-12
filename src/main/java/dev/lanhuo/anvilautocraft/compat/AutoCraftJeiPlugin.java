package dev.lanhuo.anvilautocraft.compat;

import dev.lanhuo.anvilautocraft.AnvilAutoCraft;
import dev.lanhuo.anvilautocraft.client.CraftTreeOverlay;
import java.util.Collection;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 适配：把合成树侧边栏占用的区域告诉 JEI。
 *
 * <p>通过 JEI 的全局占位接口上报面板矩形。</p>
 *
 * <p>这个类只在 JEI 存在时由 JEI 的插件扫描器加载，所以 JEI 缺席时不会触发类加载错误。</p>
 */
@JeiPlugin
public final class AutoCraftJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(AnvilAutoCraft.MOD_ID, "jei_plugin");
    private static boolean recipeButtonRegistered;
    private IJeiRuntime runtime;

    static boolean hasRecipeButton() {
        return recipeButtonRegistered;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        try {
            JeiAutoCraftButtons.register(registration, () -> runtime);
            recipeButtonRegistered = true;
        } catch (NoClassDefFoundError | NoSuchMethodError ignored) {
            // Older JEI builds use the original transfer entry point.
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {
            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                return CraftTreeOverlay.LIVE.extraAreas();
            }
        });
    }
}
