package dev.lanhuo.anvilautocraft.craft;

/**
 * 配方中某个原料槽的确定选择：具体物品与每次合成的消耗数量。
 *
 * <p>同一原料槽可能有多个可选物品（如矿物标签），规划阶段会把标签解析成具体物品，
 * 执行阶段只按具体物品扣料。</p>
 */
public record IngredientChoice(StackKey key, int perCraft) {
    public IngredientChoice {
        if (perCraft <= 0) {
            throw new IllegalArgumentException("perCraft must be positive");
        }
    }
}
