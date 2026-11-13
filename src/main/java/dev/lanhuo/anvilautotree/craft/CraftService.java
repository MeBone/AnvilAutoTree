package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.saved.storage.ShulkerContainerStorage;
import dev.lanhuo.anvilautotree.net.CraftNodePayload;
import dev.lanhuo.anvilautotree.net.CraftRequestPayload;
import dev.lanhuo.anvilautotree.net.CraftResponsePayload;
import dev.lanhuo.anvilautotree.storage.StorageLookup;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 自动合成请求的服务端入口。
 */
public final class CraftService {
    private CraftService() {
    }

    public static void handle(ServerPlayer player, CraftRequestPayload payload) {
        ServerLevel level = (ServerLevel) player.level();
        Optional<BaseStorage<?>> resolved = StorageLookup.resolve(player, payload.sourcePos());
        if (resolved.isEmpty()) {
            fail(player, payload, "anvilautotree.error.no_storage", false);
            return;
        }
        BaseStorage<?> storage = resolved.get();
        boolean shulker = storage instanceof ShulkerContainerStorage;
        if (payload.quantity() < 1 || payload.quantity() > CraftRequestPayload.MAX_QUANTITY) {
            fail(player, payload, "anvilautotree.error.invalid_quantity", shulker);
            return;
        }
        if (payload.execute() && !payload.storageId().orElseThrow().equals(storage.getId())) {
            fail(player, payload, "anvilautotree.error.storage_changed", shulker);
            return;
        }
        if (storage.getRecipeBases() == null) {
            fail(player, payload, "anvilautotree.error.not_unlocked", shulker);
            return;
        }
        Optional<? extends RecipeHolder<?>> found = level.getRecipeManager().byKey(payload.recipeId());
        if (found.isEmpty()) {
            fail(player, payload, "anvilautotree.error.unknown_recipe", shulker);
            return;
        }
        RecipeHolder<?> recipe = found.get();
        StorageAccess access = new InventoryAwareStorageAccess(new ItemHandlerStorageAccess(storage.getItems()), player);
        CraftPlan plan = CraftPlanner.plan(level, access, recipe, payload.stonecutter(), payload.quantity());
        if (!payload.execute()) {
            PacketDistributor.sendToPlayer(
                player,
                new CraftResponsePayload(
                    plan.isSuccess(),
                    CraftResponsePayload.PHASE_PLAN,
                    plan.isSuccess() ? Component.empty() : describeFailure(plan),
                    CraftNodePayload.flatten(plan.tree()),
                    new CraftResponsePayload.Storage(shulker, Optional.of(storage.getId())),
                    payload.requestId()
                )
            );
            return;
        }
        if (!plan.isSuccess()) {
            PacketDistributor.sendToPlayer(
                player,
                CraftResponsePayload.done(false, describe(plan), payload.requestId())
            );
            return;
        }
        if (plan.steps().isEmpty()) {
            PacketDistributor.sendToPlayer(
                player,
                CraftResponsePayload.done(
                    true,
                    Component.translatable("anvilautotree.preview.no_craft_needed"),
                    payload.requestId()
                )
            );
            return;
        }
        CraftJobManager.submit(new CraftJob(player, access, plan.steps(), shulker, payload.requestId(), payload.quantity()));
        PacketDistributor.sendToPlayer(player, CraftResponsePayload.accepted(payload.requestId()));
    }

    /**
     * 规划失败时面板下方只需要写「为什么失败」，缺失哪些物品已经由合成树里的红框节点表达，
     * 不再堆一长串文字。
     */
    private static Component describeFailure(CraftPlan plan) {
        if (plan.errors().isEmpty()) {
            return Component.translatable("anvilautotree.error.unsupported_recipe");
        }
        MutableComponent text = Component.empty();
        for (Component error : plan.errors()) {
            if (!text.getString().isEmpty()) {
                text.append("；");
            }
            text.append(error);
        }
        return text;
    }

    private static Component describe(CraftPlan plan) {
        MutableComponent text = Component.empty();
        for (Component line : plan.preview()) {
            text.append(line).append("\n");
        }
        for (Component error : plan.errors()) {
            text.append(error).append("\n");
        }
        if (plan.isSuccess() && plan.steps().isEmpty()) {
            text.append(Component.translatable("anvilautotree.preview.no_craft_needed"));
        }
        return text;
    }

    private static void fail(ServerPlayer player, CraftRequestPayload payload, String translationKey, boolean shulker) {
        int phase = payload.execute() ? CraftResponsePayload.PHASE_DONE : CraftResponsePayload.PHASE_PLAN;
        PacketDistributor.sendToPlayer(
            player,
            new CraftResponsePayload(false, phase, Component.translatable(translationKey), List.of(),
                new CraftResponsePayload.Storage(shulker, Optional.empty()), payload.requestId())
        );
    }
}
