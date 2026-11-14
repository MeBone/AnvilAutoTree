package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.recipe.multiblock.IMultiblockRecipe;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import dev.lanhuo.anvilautotree.net.StorageKind;
import dev.lanhuo.anvilautotree.net.CraftNodePayload;
import dev.lanhuo.anvilautotree.net.CraftRequestPayload;
import dev.lanhuo.anvilautotree.net.CraftResponsePayload;
import dev.lanhuo.anvilautotree.storage.StorageLookup;
import dev.lanhuo.anvilautotree.storage.StorageCraftingGrid;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.AutoCraftConfig;
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
        var sourcePos = payload.source().left().orElse(null);
        if (player.isSpectator() || (payload.pickBlock() && player.hasInfiniteMaterials())) {
            return;
        }
        var resolved = StorageLookup.resolve(player, payload.source());
        if (resolved.isEmpty()) {
            fail(player, payload, "anvilautotree.error.no_storage", StorageKind.HYPERDIMENSION);
            return;
        }
        var storage = resolved.get();
        StorageKind kind = StorageLookup.kindOf(storage).orElseThrow();
        if (!kind.supportsBatch() && payload.quantity() > 1) {
            fail(player, payload, "anvilautotree.error.crate_single_only", kind);
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
        Optional<? extends RecipeHolder<?>> found = level.recipeAccess().byKey(RecipeData.key(payload.recipeId()));
        if (found.isEmpty()) {
            fail(player, payload, "anvilautotree.error.unknown_recipe", kind);
            return;
        }
        var original = found.get();
        RecipeHolder<?> recipe = selectTarget(level, original, payload);
        boolean stonecutter = recipe == original ? payload.stonecutter() : recipe.value() instanceof StonecutterRecipe;
        boolean structureRecipe = recipe.value() instanceof IMultiblockRecipe;
        if (payload.quantity() < 1 || payload.quantity() > kind.maxOrderQuantity()) {
            String key = structureRecipe ? "anvilautotree.error.invalid_structure_quantity" : "anvilautotree.error.invalid_quantity";
            fail(player, payload, Component.translatable(key, kind.maxOrderQuantity()), kind);
            return;
        }
        if (payload.toGrid() && (kind != StorageKind.HYPERDIMENSION || payload.pickBlock()
            || structureRecipe || payload.quantity() <= 1)) {
            fail(player, payload, "anvilautotree.error.batch_grid_unsupported", kind);
            return;
        }
        StorageAccess stock = new ItemHandlerStorageAccess(storage.getItems());
        var result = RecipeData.result(recipe.value(), level);
        if (payload.pickBlock() && !result.isEmpty() && stock.count(result) > 0) {
            fail(player, payload, "anvilautotree.error.pick_stock", kind);
            return;
        }
        boolean useInventory = AutoCraftConfig.USE_PLAYER_INVENTORY.get();
        StorageAccess access = useInventory ? new InventoryAwareStorageAccess(stock, player) : stock;
        StructurePacking packing = null;
        CraftPlan plan;
        if (structureRecipe) {
            if (kind == StorageKind.LARGE_CRATE) {
                fail(player, payload, "anvilautotree.error.structure_storage", kind);
                return;
            }
            if (!StructurePacking.supports(recipe.value())) {
                fail(player, payload, "anvilautotree.error.unsupported_recipe", kind);
                return;
            }
            try {
                packing = StructurePacking.prepare(recipe.value(), player, access, payload.quantity(), payload.pickBlock());
                Component problem = packing.check(player, sourcePos, access);
                if (problem != null) {
                    fail(player, payload, problem, kind);
                    return;
                }
            } catch (StructurePacking.UnpackableMaterial exception) {
                fail(player, payload, "anvilautotree.error.structure_unpackable", kind);
                return;
            } catch (StructurePacking.TooManyMaterials exception) {
                fail(player, payload, payload.pickBlock() ? "anvilautotree.error.structure_inventory_size" : "anvilautotree.error.structure_size", kind);
                return;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                fail(player, payload, "anvilautotree.error.structure_grid", kind);
                return;
            }
            var icon = result.isEmpty() ? Items.SHULKER_BOX.getDefaultInstance() : result;
            plan = CraftPlanner.planStructure(level, access, recipe, icon, payload.quantity(), packing.materials(), payload.preferences());
        } else {
            plan = CraftPlanner.plan(level, access, recipe, stonecutter, payload.quantity(),
                payload.pickBlock() || payload.quantity() > 1, payload.preferences());
            if (payload.pickBlock() && !plan.isSuccess() && recipe == original
                && !payload.preferences().containsKey(RecipePreference.recipe(result))) {
                plan = tryPickAlternatives(level, access, recipe, result, payload, plan);
            }
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
        boolean preferredInputs = !plan.gridInputs().isEmpty() && java.util.stream.IntStream.range(0, RecipeData.ingredients(recipe.value()).size())
            .anyMatch(slot -> payload.preferences().containsKey(RecipePreference.ingredient(recipe.id().identifier(), slot)));
        boolean fillIngredients = (!useInventory || recipe != original || preferredInputs) && !plan.gridInputs().isEmpty();
        if (plan.steps().isEmpty() && packing == null && !fillIngredients) {
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
        if (payload.toGrid()) {
            CraftStep output = plan.steps().getLast();
            if (!placeInGrid(player, payload, output.output(), output.totalOutput().getCount(), true)) {
                fail(player, payload, "anvilautotree.error.batch_grid_space", kind);
                return;
            }
        }
        StructurePacking structure = packing;
        CraftPlan finalizedPlan = plan;
        CraftJobManager.submit(new CraftJob(player, access, plan.steps(), kind, payload.requestId(), payload.quantity(), payload.pickBlock(),
            structure == null && !fillIngredients ? null : () -> {
                if (StorageLookup.resolve(player, payload.source()).filter(current -> current == storage).isEmpty()) {
                    return Component.translatable("anvilautotree.error.storage_changed");
                }
                return fillIngredients
                    ? fillGridIngredients(player, sourcePos, finalizedPlan, recipe, access, stonecutter)
                    : structure.finish(player, sourcePos, access);
            }, payload.toGrid() ? (output, count) -> placeInGrid(player, payload, output, count, false) : null, fillIngredients));
        PacketDistributor.sendToPlayer(player, CraftResponsePayload.accepted(payload.requestId()));
    }

    private static CraftPlan tryPickAlternatives(ServerLevel level, StorageAccess access, RecipeHolder<?> original,
                                                 ItemStack result, CraftRequestPayload payload, CraftPlan initial) {
        if (result.isEmpty()) return initial;
        var alternatives = level.recipeAccess().getRecipes().stream()
            .filter(holder -> !holder.id().identifier().equals(original.id()) && RecipePreference.supports(holder.value())
                && ItemStack.isSameItemSameComponents(RecipeData.result(holder.value(), level), result))
            .sorted(java.util.Comparator.comparing(RecipeHolder::id)).toList();
        CraftPlan display = initial;
        for (var candidate : alternatives) {
            var plan = CraftPlanner.plan(level, access, candidate, candidate.value() instanceof StonecutterRecipe,
                payload.quantity(), true, payload.preferences());
            if (plan.isSuccess()) return plan;
            if (display.tree() == null && plan.tree() != null) display = plan;
        }
        return display;
    }

    private static RecipeHolder<?> selectTarget(ServerLevel level, RecipeHolder<?> original, CraftRequestPayload payload) {
        if (!RecipePreference.supports(original.value())) return original;
        ItemStack result = RecipeData.result(original.value(), level);
        var preferred = payload.preferences().get(RecipePreference.recipe(result));
        return preferred == null ? original : level.recipeAccess().byKey(RecipeData.key(preferred))
            .filter(holder -> RecipePreference.supports(holder.value()) && ItemStack.isSameItemSameComponents(
                RecipeData.result(holder.value(), level), result))
            .orElse(original);
    }

    private static Component fillGridIngredients(ServerPlayer player, net.minecraft.core.BlockPos pos,
                                                 CraftPlan plan, RecipeHolder<?> recipe, StorageAccess access, boolean stonecutter) {
        var grid = StorageCraftingGrid.resolve(player, pos);
        if (grid.isEmpty()) return Component.translatable("anvilautotree.error.ingredients_grid");
        try {
            return grid.get().fillIngredients(player, recipe, plan.gridInputs(), access, stonecutter)
                ? null : Component.translatable("anvilautotree.error.ingredients_grid");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to fill AnvilCraft's grid from storage", exception);
            return Component.translatable("anvilautotree.error.ingredients_grid");
        }
    }

    private static boolean placeInGrid(ServerPlayer player, CraftRequestPayload payload, net.minecraft.world.item.ItemStack output,
                                       int count, boolean simulate) {
        var pos = payload.source().left().orElseThrow();
        if (StorageLookup.resolve(player, pos).filter(storage -> payload.storageId().orElseThrow().equals(storage.getId())).isEmpty()) {
            return false;
        }
        var grid = StorageCraftingGrid.resolve(player, pos);
        if (grid.isEmpty()) return false;
        try {
            return grid.get().insertOutput(output, count, simulate);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to send crafting output to AnvilCraft's grid", exception);
            return false;
        }
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
