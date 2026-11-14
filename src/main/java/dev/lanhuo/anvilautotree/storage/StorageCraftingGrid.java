package dev.lanhuo.anvilautotree.storage;

import dev.dubhe.anvilcraft.rpc.StorageServerStub;
import dev.dubhe.anvilcraft.saved.storage.CraftingStorage;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.craft.StackKey;
import dev.lanhuo.anvilautotree.craft.StorageAccess;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/** Uses the same crafting-grid target as AnvilCraft's own transfer. */
public final class StorageCraftingGrid {
    private static final Access ACCESS = discover();
    private final Object target;

    private StorageCraftingGrid(Object target) {
        this.target = target;
    }

    public static Optional<StorageCraftingGrid> resolve(ServerPlayer player, BlockPos pos) {
        if (ACCESS == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            var registries = (ThreadLocal<HolderLookup.Provider>) ACCESS.registries().get(null);
            var previous = registries.get();
            try {
                registries.set(player.registryAccess());
                return Optional.of(new StorageCraftingGrid(ACCESS.resolve().invoke(null, player, pos.asLong())));
            } finally {
                if (previous == null) registries.remove();
                else registries.set(previous);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to resolve AnvilCraft crafting grid", exception);
            return Optional.empty();
        }
    }

    public CraftingStorage read() throws ReflectiveOperationException {
        return (CraftingStorage) ACCESS.read().invoke(target);
    }

    public void write(CraftingStorage grid) throws ReflectiveOperationException {
        ACCESS.write().invoke(target, grid);
    }

    public boolean fillIngredients(ServerPlayer player, RecipeHolder<?> recipe, List<ItemStack> inputs,
                                   StorageAccess storage, boolean stonecutter) throws ReflectiveOperationException {
        CraftingStorage before = read();
        CraftingStorage after;
        List<ItemStack> replaced;
        if (stonecutter) {
            var input = inputs.getFirst();
            var recipes = player.level().getRecipeManager().getRecipesFor(
                RecipeType.STONECUTTING, new SingleRecipeInput(input), player.level());
            int index = recipes.indexOf(recipe);
            if (index < 0) return false;
            after = before.withStonecutterInput(input.copy()).withStonecutterSelected(index);
            replaced = List.of(before.stonecutterInput());
        } else {
            if (!(recipe.value() instanceof CraftingRecipe crafting)
                || !crafting.matches(CraftingInput.of(3, 3, inputs), player.level())) return false;
            after = before.withCraftingInput(inputs.stream().map(ItemStack::copy).toList());
            replaced = before.craftingInput();
        }

        var required = new LinkedHashMap<StackKey, Integer>();
        for (ItemStack input : inputs) {
            if (!input.isEmpty()) required.merge(StackKey.of(input), input.getCount(), Integer::sum);
        }
        for (var entry : required.entrySet()) {
            if (storage.count(entry.getKey().proto()) < entry.getValue()) return false;
        }

        var removed = new ArrayList<ItemStack>();
        boolean written = false;
        try {
            for (var entry : required.entrySet()) {
                ItemStack input = entry.getKey().proto();
                int taken = storage.extract(input, entry.getValue(), false);
                if (taken > 0) removed.add(input.copyWithCount(taken));
                if (taken != entry.getValue()) return false;
            }
            write(after);
            written = true;
        } finally {
            if (!written) returnItems(player, storage, removed);
        }
        returnItems(player, storage, replaced);
        return true;
    }

    private static void returnItems(ServerPlayer player, StorageAccess storage, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            ItemStack leftover = storage.insert(stack.copy());
            if (!leftover.isEmpty()) player.getInventory().placeItemBackInInventory(leftover);
        }
    }

    /** Places the entire batch in the nine crafting input slots if it fits. */
    public boolean insertOutput(ItemStack output, int count, boolean simulate) throws ReflectiveOperationException {
        CraftingStorage grid = read();
        var slots = new ArrayList<>(grid.craftingInput());
        int limit = output.getMaxStackSize();
        for (boolean useEmpty : new boolean[]{false, true}) {
            for (int slot = 0; slot < slots.size() && count > 0; slot++) {
                ItemStack current = slots.get(slot);
                if (useEmpty ? !current.isEmpty() : !ItemStack.isSameItemSameComponents(current, output)) {
                    continue;
                }
                int inserted = Math.min(count, Math.max(0, limit - current.getCount()));
                if (inserted > 0) {
                    slots.set(slot, output.copyWithCount(current.getCount() + inserted));
                    count -= inserted;
                }
            }
        }
        if (count > 0) return false;
        if (!simulate) write(grid.withCraftingInput(slots));
        return true;
    }

    private static Access discover() {
        try {
            Field registries = StorageServerStub.class.getDeclaredField("REGISTRIES");
            Method resolve = StorageServerStub.class.getDeclaredMethod("resolveCraftingTarget", ServerPlayer.class, long.class);
            Method read = resolve.getReturnType().getDeclaredMethod("read");
            Method write = resolve.getReturnType().getDeclaredMethod("write", CraftingStorage.class);
            for (var member : new java.lang.reflect.AccessibleObject[]{registries, resolve, read, write}) {
                member.setAccessible(true);
            }
            return new Access(registries, resolve, read, write);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            AnvilAutoTree.LOGGER.warn("AnvilCraft crafting-grid interface unavailable", exception);
            return null;
        }
    }

    private record Access(Field registries, Method resolve, Method read, Method write) {
    }
}
