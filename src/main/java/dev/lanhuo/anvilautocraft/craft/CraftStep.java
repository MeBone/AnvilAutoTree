package dev.lanhuo.anvilautocraft.craft;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 合成计划中的一步：把 {@code crafts} 次同一配方的合成合并成一次批量操作。
 *
 * <p>步骤按「子步骤在前、父步骤在后」排列，执行时顺序推进即可保证原料已经就位。</p>
 *
 * @param ingredients 每次合成消耗的原料
 * @param output 每次合成的产物（数量已按配方写出）
 * @param crafts 本步骤合并的合成次数
 * @param stonecutter 是否来自切石机配方
 * @param recipeId 配方标识，仅用于日志与提示
 */
public record CraftStep(List<IngredientChoice> ingredients, ItemStack output, int crafts, boolean stonecutter, ResourceLocation recipeId) {
    public CraftStep {
        ingredients = List.copyOf(ingredients);
        if (crafts <= 0) {
            throw new IllegalArgumentException("crafts must be positive");
        }
    }

    public ItemStack totalOutput() {
        return this.output.copyWithCount(this.output.getCount() * this.crafts);
    }
}
