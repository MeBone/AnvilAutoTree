package dev.lanhuo.anvilautotree.client;

import dev.dubhe.anvilcraft.client.rpc.StorageTerminalClientStub;
import dev.dubhe.anvilcraft.client.rpc.TerminalJeiStorageCache;
import dev.dubhe.anvilcraft.init.item.ModComponents;
import dev.dubhe.anvilcraft.item.HyperdimensionTerminalItem;
import dev.dubhe.anvilcraft.item.TerminalItem;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockConversionRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockUtil;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class TerminalPickBlock {
    private static LocalPlayer awaitingStock;

    private TerminalPickBlock() {
    }

    static boolean tryPick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null || player.isSpectator()
            || player.hasInfiniteMaterials() || minecraft.screen != null || !AutoCraftClient.canStartPickBlock()
            || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (awaitingStock == player) {
            return true;
        }
        UUID storageId = TerminalItem.getAll(player).stream()
            .filter(stack -> stack.getItem() instanceof HyperdimensionTerminalItem)
            .map(stack -> stack.get(ModComponents.TERMINAL_BINDING))
            .filter(Objects::nonNull)
            .flatMap(binding -> binding.id().stream())
            .findFirst().orElse(null);
        if (storageId == null) {
            return false;
        }
        var state = level.getBlockState(hit.getBlockPos());
        ItemStack target = state.getCloneItemStack(hit, level, hit.getBlockPos(), player);
        if (target.isEmpty() || player.getInventory().findSlotMatchingItem(target) != -1) {
            return false;
        }
        RecipeHolder<?> recipe = level.getRecipeManager().getRecipes().stream()
            .filter(holder -> produces(level, holder.value(), target))
            .min(Comparator.<RecipeHolder<?>>comparingInt(holder -> priority(holder.value()))
                .thenComparing(RecipeHolder::id))
            .orElse(null);
        if (recipe == null) {
            return false;
        }

        awaitingStock = player;
        // Preserve AnvilCraft's withdrawal and hotbar selection before offering a craft.
        List<UUID> storages = TerminalJeiStorageCache.boundStorages(player);
        StorageTerminalClientStub.withdrawToInventory(storages, List.of(target.copyWithCount(target.getMaxStackSize())))
            .whenComplete((withdrawn, error) -> minecraft.execute(() -> {
                if (awaitingStock != player) return;
                awaitingStock = null;
                if (error != null || minecraft.player != player || minecraft.level != level || minecraft.screen != null) {
                    return;
                }
                int slot = player.getInventory().findSlotMatchingItem(target);
                if (slot < 0 && !withdrawn.isEmpty()) {
                    return;
                }
                if (Inventory.isHotbarSlot(slot)) {
                    player.getInventory().selected = slot;
                } else if (slot >= 0 && minecraft.gameMode != null) {
                    minecraft.gameMode.handlePickItem(slot);
                } else if (AutoCraftClient.canStartPickBlock()) {
                    AutoCraftClient.planPickBlock(storageId, recipe, player);
                }
            }));
        return true;
    }

    private static int priority(Recipe<?> recipe) {
        return switch (recipe) {
            case ShapedRecipe ignored -> 0;
            case ShapelessRecipe ignored -> 0;
            case StonecutterRecipe ignored -> 1;
            default -> 2;
        };
    }

    private static boolean produces(ClientLevel level, Recipe<?> recipe, ItemStack target) {
        var registries = level.registryAccess();
        return switch (recipe) {
            case ShapedRecipe ignored -> ItemStack.isSameItemSameComponents(recipe.getResultItem(registries), target);
            case ShapelessRecipe ignored -> ItemStack.isSameItemSameComponents(recipe.getResultItem(registries), target);
            case StonecutterRecipe ignored -> ItemStack.isSameItemSameComponents(recipe.getResultItem(registries), target);
            case MultiblockRecipe ignored -> ItemStack.isSameItemSameComponents(recipe.getResultItem(registries), target);
            case MultiblockConversionRecipe conversion -> MultiblockUtil.ingredientList(conversion.getOutputPattern(), registries)
                .stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, target));
            default -> false;
        };
    }
}
