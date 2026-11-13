package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockConversionRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockUtil;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.storage.StorageCraftingGrid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

final class StructurePacking {
    private static final int BOX_SLOTS = 27;
    private static final int GRID_SLOTS = 9;
    private static final List<ItemStack> EMPTY_BOXES = emptyBoxes();

    private final List<IngredientChoice> materials;
    private final List<ItemStack> stacks;
    private final int quantity;
    private final boolean toInventory;

    private StructurePacking(List<IngredientChoice> materials, List<ItemStack> stacks, int quantity, boolean toInventory) {
        this.materials = List.copyOf(materials);
        this.stacks = List.copyOf(stacks);
        this.quantity = quantity;
        this.toInventory = toInventory;
    }

    static StructurePacking prepare(Recipe<?> recipe, ServerPlayer player, StorageAccess access, int quantity, boolean toInventory) {
        var pattern = switch (recipe) {
            case MultiblockRecipe crafting -> crafting.getPattern();
            case MultiblockConversionRecipe conversion -> conversion.getInputPattern();
            default -> throw new IllegalArgumentException("Unsupported structure recipe");
        };
        var totals = new LinkedHashMap<StackKey, Long>();
        for (ItemStack stack : MultiblockUtil.ingredientList(pattern, player.registryAccess())) {
            if (!stack.isEmpty()) totals.merge(StackKey.of(stack), (long) stack.getCount(), Long::sum);
        }
        for (var entry : MultiblockUtil.tagIngredientCounts(pattern).entrySet()) {
            var options = player.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                .getTag(entry.getKey()).orElseThrow();
            var choice = options.stream().map(block -> new ItemStack(block.value().asItem()))
                .filter(StructurePacking::canPack)
                .max(java.util.Comparator.comparingLong(stack ->
                    access.count(stack) - totals.getOrDefault(StackKey.of(stack), 0L) * quantity))
                .orElseThrow(UnpackableMaterial::new);
            totals.merge(StackKey.of(choice), (long) entry.getValue(), Long::sum);
        }
        var materials = new ArrayList<IngredientChoice>();
        var stacks = new ArrayList<ItemStack>();
        int maxBoxes = toInventory ? Inventory.INVENTORY_SIZE : GRID_SLOTS;
        totals.forEach((key, count) -> {
            ItemStack stack = key.proto();
            if (!canPack(stack)) throw new UnpackableMaterial();
            long total = count * quantity;
            long neededSlots = Math.ceilDiv(total, stack.getMaxStackSize());
            if (neededSlots > BOX_SLOTS * maxBoxes - stacks.size()) throw new TooManyMaterials();
            materials.add(new IngredientChoice(key, Math.toIntExact(count)));
            for (long remaining = total; remaining > 0;) {
                int amount = (int) Math.min(remaining, stack.getMaxStackSize());
                stacks.add(stack.copyWithCount(amount));
                remaining -= amount;
            }
        });
        return new StructurePacking(materials, stacks, quantity, toInventory);
    }

    private static boolean canPack(ItemStack stack) {
        return !stack.isEmpty() && !(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)
            && stack.canFitInsideContainerItems();
    }

    static final class UnpackableMaterial extends RuntimeException {
    }

    static final class TooManyMaterials extends RuntimeException {
    }

    List<IngredientChoice> materials() {
        return materials;
    }

    int boxes() {
        return Math.ceilDiv(stacks.size(), BOX_SLOTS);
    }

    Component check(ServerPlayer player, BlockPos pos, StorageAccess access) throws ReflectiveOperationException {
        if (!stacks.stream().allMatch(StructurePacking::canPack)) {
            return Component.translatable("anvilautotree.error.structure_unpackable");
        }
        if (boxes() == 0) {
            return Component.translatable("anvilautotree.error.structure_size");
        }
        int available = 0;
        for (var box : EMPTY_BOXES) {
            available += (int) Math.min(boxes() - available, access.count(box));
        }
        if (available < boxes()) {
            return Component.translatable("anvilautotree.error.structure_boxes", boxes() - available);
        }
        if (toInventory) return null;
        var grid = StorageCraftingGrid.resolve(player, pos).orElse(null);
        if (grid == null) return Component.translatable("anvilautotree.error.structure_grid");
        long slots = grid.read().craftingInput().stream().filter(ItemStack::isEmpty).count();
        return slots < boxes() ? Component.translatable("anvilautotree.error.structure_slots", boxes() - slots) : null;
    }

    Component finish(ServerPlayer player, BlockPos pos, StorageAccess access) {
        var removed = new ArrayList<ItemStack>();
        boolean committed = false;
        try {
            Component error = check(player, pos, access);
            if (error != null) return error;
            var boxes = new ArrayList<ItemStack>();
            for (var box : EMPTY_BOXES) {
                int taken = access.extract(box, boxes() - boxes.size(), false);
                if (taken > 0) removed.add(box.copyWithCount(taken));
                for (int i = 0; i < taken; i++) boxes.add(box.copy());
                if (boxes.size() == boxes()) break;
            }
            if (boxes.size() != boxes()) return Component.translatable("anvilautotree.error.structure_boxes", boxes() - boxes.size());
            for (var material : materials) {
                var stack = material.key().proto();
                int required = material.perCraft() * quantity;
                int taken = access.extract(stack, required, false);
                if (taken > 0) removed.add(stack.copyWithCount(taken));
                if (taken != required) return Component.translatable("anvilautotree.status.failed");
            }
            for (int index = 0; index < boxes.size(); index++) {
                boxes.get(index).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                    stacks.subList(index * BOX_SLOTS, Math.min(stacks.size(), (index + 1) * BOX_SLOTS))));
            }
            if (toInventory) {
                var inventory = player.getInventory();
                var slots = new ArrayList<Integer>();
                for (int slot = 0; slot < Inventory.INVENTORY_SIZE && slots.size() < boxes.size(); slot++) {
                    if (inventory.getItem(slot).isEmpty()) slots.add(slot);
                }
                if (slots.size() < boxes.size()) {
                    return Component.translatable("anvilautotree.error.structure_inventory_slots", boxes.size() - slots.size());
                }
                for (int index = 0; index < boxes.size(); index++) {
                    inventory.setItem(slots.get(index), boxes.get(index));
                }
                inventory.setChanged();
            } else {
                var target = StorageCraftingGrid.resolve(player, pos).orElseThrow();
                var grid = target.read();
                var slots = new ArrayList<>(grid.craftingInput());
                int slot = 0;
                for (var box : boxes) {
                    while (!slots.get(slot).isEmpty()) slot++;
                    slots.set(slot++, box);
                }
                target.write(grid.withCraftingInput(slots));
            }
            committed = true;
            return null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to pack structure materials", exception);
            return Component.translatable("anvilautotree.error.structure_grid");
        } finally {
            if (!committed) {
                for (var stack : removed) {
                    ItemStack remainder = access.insert(stack);
                    if (!remainder.isEmpty()) player.getInventory().add(remainder);
                    if (!remainder.isEmpty()) player.drop(remainder, false);
                }
            }
        }
    }

    private static List<ItemStack> emptyBoxes() {
        var boxes = new ArrayList<ItemStack>();
        boxes.add(Items.SHULKER_BOX.getDefaultInstance());
        net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.SHULKER_BOX && item instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock)
            .map(item -> item.getDefaultInstance()).forEach(boxes::add);
        return boxes;
    }
}
