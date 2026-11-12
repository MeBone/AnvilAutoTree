package dev.lanhuo.anvilautocraft.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 仅在客户端应用 JEI 相关 mixin，避免专用服务器加载客户端类。
 */
public final class AutoCraftMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FMLEnvironment.dist == Dist.CLIENT && jeiLoaded();
    }

    /**
     * 判断 JEI 是否在场。
     *
     * <p>mixin 配置在模组列表完成加载之前就可能被准备，此时 {@link ModList#get()} 为 null，
     * 直接调用会抛空指针并让整份 mixin 配置被丢弃，所以先退化到早期的 {@link LoadingModList}；
     * 两者都拿不到时按「在场」处理，由 mixin 配置的 {@code required: false} 与目标类存在性兜底。</p>
     */
    private static boolean jeiLoaded() {
        try {
            ModList mods = ModList.get();
            if (mods != null) {
                return mods.isLoaded("jei");
            }
            LoadingModList loading = LoadingModList.get();
            if (loading != null) {
                return loading.getModFileById("jei") != null;
            }
        } catch (Throwable ignored) {
            // 早期加载阶段各 API 可能尚未就绪，按在场处理
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
