package dev.lanhuo.anvilautotree;

import com.mojang.logging.LogUtils;
import dev.lanhuo.anvilautotree.craft.CraftJobManager;
import dev.lanhuo.anvilautotree.net.ModPayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * 模组入口。
 *
 * <p>本模组不注册方块、物品与配方：客户端在 JEI 快捷合成缺料时弹出确认，
 * 服务端按合成链在后台模拟合成面板补齐缺失材料，最后回执通知客户端重放终端转移。</p>
 */
@Mod(AnvilAutoTree.MOD_ID)
public final class AnvilAutoTree {
    public static final String MOD_ID = "anvilautotree";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnvilAutoTree(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, AutoCraftConfig.SPEC);
        modBus.addListener(ModPayloads::register);
        NeoForge.EVENT_BUS.addListener(AnvilAutoTree::onServerTick);
        NeoForge.EVENT_BUS.addListener(AnvilAutoTree::onServerStopping);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        CraftJobManager.tick(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CraftJobManager.clear();
    }
}
