package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.recipe.multiblock.IMultiblockRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockConversionRecipe;
import net.minecraft.world.item.Items;
import dev.lanhuo.anvilautotree.net.StorageKind;
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
            fail(player, payload, "anvilautotree.error.no_storage", StorageKind.HYPERDIMENSION);
            return;
        }
        BaseStorage<?> storage = resolved.get();
        StorageKind kind = StorageLookup.kindOf(storage).orElseThrow();
        if (!kind.supportsBatch() && payload.quantity() > 1) {
            fail(player, payload, "anvilautotree.error.crate_single_only", kind);
            return;
        }
        if (payload.quantity() < 1 || payload.quantity() > CraftRequestPayload.MAX_QUANTITY) {
            fail(player, payload, "anvilautotree.error.invalid_quantity", kind);
            return;
        }
        if (payload.execute() && !payload.storageId().orElseThrow().equals(storage.getId())) {
            fail(player, payload, "anvilautotree.error.storage_changed", kind);
            return;
        }
        if (storage.getRecipeBases() == null) {
            fail(player, payload, "anvilautotree.error.not_unlocked", kind);
            return;
        }
        Optional<? extends RecipeHolder<?>> found = level.getRecipeManager().byKey(payload.recipeId());
        if (found.isEmpty()) {
            fail(player, payload, "anvilautotree.error.unknown_recipe", kind);
            return;
        }
        RecipeHolder<?> recipe = found.get();
        StorageAccess access = new InventoryAwareStorageAccess(new ItemHandlerStorageAccess(storage.getItems()), player);
        StructurePacking packing = null;
        CraftPlan plan;
        if (recipe.value() instanceof IMultiblockRecipe) {
            if (kind == StorageKind.LARGE_CRATE || payload.quantity() != 1) {
                fail(player, payload, "anvilautotree.error.structure_storage", kind);
                return;
            }
            if (!(recipe.value() instanceof MultiblockRecipe || recipe.value() instanceof MultiblockConversionRecipe)) {
                fail(player, payload, "anvilautotree.error.unsupported_recipe", kind);
                return;
            }
            try {
                packing = StructurePacking.prepare(recipe.value(), player, access);
                Component problem = packing.check(player, payload.sourcePos(), access);
                if (problem != null) {
                    fail(player, payload, problem, kind);
                    return;
                }
            } catch (StructurePacking.UnpackableMaterial exception) {
                fail(player, payload, "anvilautotree.error.structure_unpackable", kind);
                return;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                fail(player, payload, "anvilautotree.error.structure_grid", kind);
                return;
            }
            var icon = recipe.value().getResultItem(level.registryAccess());
            if (icon.isEmpty()) icon = Items.SHULKER_BOX.getDefaultInstance();
            plan = CraftPlanner.planStructure(level, access, recipe, icon, packing.materials());
        } else {
            plan = CraftPlanner.plan(level, access, recipe, payload.stonecutter(), payload.quantity());
        }
        if (!payload.execute()) {
            PacketDistributor.sendToPlayer(
                player,
                new CraftResponsePayload(
                    plan.isSuccess(),
                    CraftResponsePayload.PHASE_PLAN,
                    plan.isSuccess() ? Component.empty() : describeFailure(plan),
                    CraftNodePayload.flatten(plan.tree()),
                    new CraftResponsePayload.Storage(kind, Optional.of(storage.getId())),
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
        if (plan.steps().isEmpty() && packing == null) {
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
        StructurePacking structure = packing;
        CraftJobManager.submit(new CraftJob(player, access, plan.steps(), kind, payload.requestId(), payload.quantity(),
            structure == null ? null : () -> structure.finish(player, payload.sourcePos(), storage, access)));
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

    private static void fail(ServerPlayer player, CraftRequestPayload payload, String translationKey, StorageKind kind) {
        fail(player, payload, Component.translatable(translationKey), kind);
    }

    private static void fail(ServerPlayer player, CraftRequestPayload payload, Component message, StorageKind kind) {
        int phase = payload.execute() ? CraftResponsePayload.PHASE_DONE : CraftResponsePayload.PHASE_PLAN;
        PacketDistributor.sendToPlayer(
            player,
            new CraftResponsePayload(false, phase, message, List.of(),
                new CraftResponsePayload.Storage(kind, Optional.empty()), payload.requestId())
        );
    }
}
