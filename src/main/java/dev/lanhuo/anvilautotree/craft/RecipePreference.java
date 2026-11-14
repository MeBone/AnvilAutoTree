package dev.lanhuo.anvilautotree.craft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

public final class RecipePreference {
    public static final int MAX_ENTRIES = 256;

    private RecipePreference() {}

    public static String recipe(ItemStack output) {
        return "recipe/" + item(output);
    }

    public static String ingredient(ResourceLocation recipe, int slot) {
        return "ingredient/" + recipe + "/" + slot;
    }

    public static boolean supports(Recipe<?> recipe) {
        return !recipe.isSpecial() && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe
            || recipe instanceof StonecutterRecipe);
    }

    static boolean supportsDirectCraft(Recipe<?> recipe) {
        if (recipe.isSpecial()) return false;
        if (recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class
            || recipe.getClass() == StonecutterRecipe.class) return true;

        String type = recipe.getClass().getName();
        if (!type.equals("dev.latvian.mods.kubejs.recipe.special.ShapedKubeJSRecipe")
            && !type.equals("dev.latvian.mods.kubejs.recipe.special.ShapelessKubeJSRecipe")) return false;
        try {
            var actions = recipe.getClass().getMethod("kjs$getIngredientActions").invoke(recipe);
            var modifier = recipe.getClass().getMethod("kjs$getModifyResult").invoke(recipe);
            return actions instanceof java.util.List<?> list && list.isEmpty()
                && (modifier == null || "".equals(modifier));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    public static ResourceLocation item(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
}
