package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.recipe.multiblock.Multiblock4DRecipe;
import dev.dubhe.anvilcraft.recipe.multiblock.MultiblockRecipe;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

public final class RecipeData {
    private RecipeData() {}

    public static ResourceKey<Recipe<?>> key(Identifier id) {
        return ResourceKey.create(Registries.RECIPE, id);
    }

    public static List<Optional<Ingredient>> ingredients(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getIngredients();
        return recipe.placementInfo().ingredients().stream().map(Optional::of).toList();
    }

    public static List<ItemStack> options(Ingredient ingredient, Level level) {
        return ingredient.display().resolveForStacks(SlotDisplayContext.fromLevel(level));
    }

    public static ItemStack result(Recipe<?> recipe, Level level) {
        return switch (recipe) {
            case MultiblockRecipe multiblock -> multiblock.getResultItem();
            case Multiblock4DRecipe phased -> phased.getResultItem();
            default -> recipe.display().isEmpty() ? ItemStack.EMPTY
                : recipe.display().getFirst().result().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
        };
    }
}
