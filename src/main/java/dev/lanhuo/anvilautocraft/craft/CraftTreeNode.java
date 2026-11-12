package dev.lanhuo.anvilautocraft.craft;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * 合成树的展示节点（由规划器在规划过程中一并生成，供确认界面的合成树使用）。
 *
 * @param stack    该节点代表的物品（数量固定为 1，仅用于渲染图标）
 * @param required 父级配方这一次合成需要的数量
 * @param stored   规划开始时存储与背包里的现有数量
 * @param crafts   本计划中该物品需要合成的次数，0 表示不需要合成
 * @param missing  该物品缺口无法由合成补齐（没有可用配方，或陷入自循环）
 * @param children 需要合成时，其配方原料对应的子节点
 */
public record CraftTreeNode(
    ItemStack stack,
    long required,
    long stored,
    int crafts,
    boolean missing,
    List<CraftTreeNode> children
) {
    public CraftTreeNode {
        stack = stack.copyWithCount(1);
        children = List.copyOf(children);
    }

    public static CraftTreeNode leaf(ItemStack stack, long required, long stored) {
        return new CraftTreeNode(stack, required, stored, 0, false, List.of());
    }

    /**
     * 缺口无法补齐的物品：以红框节点展示，让玩家直接看到缺的是什么。
     *
     * @param children 这条断掉的合成分支上的下一层（缺失物品自身没有子节点）
     */
    public static CraftTreeNode missing(ItemStack stack, long required, long stored, List<CraftTreeNode> children) {
        return new CraftTreeNode(stack, required, stored, 0, true, children);
    }

    /**
     * 该节点是否完全由现有库存覆盖。
     */
    public boolean satisfied() {
        return this.crafts == 0 && this.stored >= this.required;
    }
}
