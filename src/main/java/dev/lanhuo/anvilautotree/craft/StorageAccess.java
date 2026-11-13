package dev.lanhuo.anvilautotree.craft;

import net.minecraft.world.item.ItemStack;

/**
 * 超维存储的最小访问抽象，便于规划器与执行器脱离具体的物品处理器实现。
 */
public interface StorageAccess {
    /**
     * 统计存储中指定物品的总数量。
     */
    long count(ItemStack proto);

    /**
     * 提取指定物品，返回实际提取数量。
     *
     * @param simulate 为 {@code true} 时只模拟，不修改存储
     */
    int extract(ItemStack proto, int amount, boolean simulate);

    /**
     * 插入物品，返回未能插入的余量。
     */
    ItemStack insert(ItemStack stack);
}
