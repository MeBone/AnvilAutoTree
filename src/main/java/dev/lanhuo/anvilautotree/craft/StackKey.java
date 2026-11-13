package dev.lanhuo.anvilautotree.craft;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * 以「物品 + 数据组件」为键的栈标识。
 *
 * <p>{@link ItemStack} 默认按引用比较，不能直接作为 Map 的键，因此包装为单件原型并按
 * {@link ItemStack#isSameItemSameComponents(ItemStack, ItemStack)} 比较。</p>
 */
public record StackKey(ItemStack proto) {
    private static final StackKey EMPTY = new StackKey(ItemStack.EMPTY);

    public StackKey {
        Objects.requireNonNull(proto, "proto");
        proto = proto.copyWithCount(1);
    }

    public static StackKey of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new StackKey(stack);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StackKey key && ItemStack.isSameItemSameComponents(this.proto, key.proto);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(this.proto);
    }
}
