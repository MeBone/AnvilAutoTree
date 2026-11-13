package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockConversionRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockUtil;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.storage.StorageCraftingGrid;
import dev.lanhuo.anvilautotree.storage.StorageLookup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.ShulkerBoxBlock;

final class StructurePacking {
    private static final int BOX_SLOTS = 27;
    private static final int GRID_SLOTS = 9;
    private static final List<ItemStack> EMPTY_BOXES = emptyBoxes();

    private final List<IngredientChoice> materials;
    private final List<ItemStack> stacks;

    private StructurePacking(List<IngredientChoice> materials, List<ItemStack> stacks) {
        this.materials = List.copyOf(materials);
        this.stacks = List.copyOf(stacks);
    }

    static StructurePacking prepare(Recipe<?> recipe, ServerPlayer player, StorageAccess access) {
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
                .filter(stack -> !stack.isEmpty())
                .max(java.util.Comparator.comparingLong(stack -> access.count(stack) - totals.getOrDefault(StackKey.of(stack), 0L)))
                .orElseThrow();
            totals.merge(StackKey.of(choice), (long) entry.getValue(), Long::sum);
        }
        var materials = new ArrayList<IngredientChoice>();
        var stacks = new ArrayList<ItemStack>();
        totals.forEach((key, count) -> {
            ItemStack stack = key.proto();
            if (!stack.getItem().canFitInsideContainerItems()) throw new IllegalArgumentException("Unpackable material");
            if (count > (long) BOX_SLOTS * GRID_SLOTS * stack.getMaxStackSize()) throw new IllegalArgumentException("Too many materials");
            materials.add(new IngredientChoice(key, Math.toIntExact(count)));
            for (long remaining = count; remaining > 0;) {
                int amount = (int) Math.min(remaining, stack.getMaxStackSize());
                stacks.add(stack.copyWithCount(amount));
                remaining -= amount;
            }
        });
        return new StructurePacking(materials, stacks);
    }

    List<IngredientChoice> materials() {
        return materials;
    }

    int boxes() {
        return Math.ceilDiv(stacks.size(), BOX_SLOTS);
    }

    Component check(ServerPlayer player, BlockPos pos, StorageAccess access) throws ReflectiveOperationException {
        if (boxes() == 0 || boxes() > GRID_SLOTS) {
            return Component.translatable("anvilautotree.error.structure_size");
        }
        int available = 0;
        for (var box : EMPTY_BOXES) {
            available += (int) Math.min(boxes() - available, access.count(box));
        }
        if (available < boxes()) {
            return Component.translatable("anvilautotree.error.structure_boxes", boxes() - available);
        }
        var grid = StorageCraftingGrid.resolve(player, pos).orElse(null);
        if (grid == null) return Component.translatable("anvilautotree.error.structure_grid");
        long slots = grid.read().craftingInput().stream().filter(ItemStack::isEmpty).count();
        return slots < boxes() ? Component.translatable("anvilautotree.error.structure_slots", boxes() - slots) : null;
    }

    Component finish(ServerPlayer player, BlockPos pos, BaseStorage<?> storage, StorageAccess access) {
        if (StorageLookup.resolve(player, pos).filter(current -> current == storage).isEmpty()) {
            return Component.translatable("anvilautotree.error.storage_changed");
        }
        var removed = new ArrayList<ItemStack>();
        boolean committed = false;
        try {
            Component error = check(player, pos, access);
            if (error != null) return error;
            var target = StorageCraftingGrid.resolve(player, pos).orElseThrow();
            var grid = target.read();
            var slots = new ArrayList<>(grid.craftingInput());
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
                int taken = access.extract(stack, material.perCraft(), false);
                if (taken > 0) removed.add(stack.copyWithCount(taken));
                if (taken != material.perCraft()) return Component.translatable("anvilautotree.status.failed");
            }
            int slot = 0;
            for (int index = 0; index < boxes.size(); index++) {
                while (!slots.get(slot).isEmpty()) slot++;
                ItemStack box = boxes.get(index);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                    stacks.subList(index * BOX_SLOTS, Math.min(stacks.size(), (index + 1) * BOX_SLOTS))));
                slots.set(slot++, box);
            }
            target.write(grid.withCraftingInput(slots));
            committed = true;
            return null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to pack structure materials", exception);
            return Component.translatable("anvilautotree.error.structure_grid");
        } finally {
            if (!committed) {
                for (var stack : removed) {
                    ItemStack remainder = access.insert(stack);
                    if (!remainder.isEmpty() && !player.getInventory().add(remainder)) player.drop(remainder, false);
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
