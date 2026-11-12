package dev.lanhuo.anvilautocraft.craft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 把玩家背包计入可用数量的存储访问包装。
 *
 * <p>JEI 判定缺料时统计的是「背包 + 终端存储」，若这里只按存储计数，会多造出玩家背包里本来就有的
 * 那一份；因此计数与扣料都把背包当作次级来源：先扣存储，存储不够时再从背包补足。</p>
 *
 * <p>产物仍然只写入存储（写不下时由调用方退回背包），与终端自身合成面板的行为一致。</p>
 */
public final class InventoryAwareStorageAccess implements StorageAccess {
    private final StorageAccess delegate;
    private final ServerPlayer player;

    public InventoryAwareStorageAccess(StorageAccess delegate, ServerPlayer player) {
        this.delegate = delegate;
        this.player = player;
    }

    @Override
    public long count(ItemStack stack) {
        return this.delegate.count(stack) + this.countInInventory(stack);
    }

    @Override
    public int extract(ItemStack stack, int amount, boolean simulate) {
        int fromStorage = this.delegate.extract(stack, amount, simulate);
        if (fromStorage >= amount) {
            return fromStorage;
        }
        int fromInventory = Math.min(amount - fromStorage, this.countInInventory(stack));
        if (fromInventory <= 0) {
            return fromStorage;
        }
        if (!simulate) {
            this.removeFromInventory(stack, fromInventory);
        }
        return fromStorage + fromInventory;
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        return this.delegate.insert(stack);
    }

    private int countInInventory(ItemStack stack) {
        int total = 0;
        for (int slot = 0; slot < this.player.getInventory().getContainerSize(); slot++) {
            ItemStack inSlot = this.player.getInventory().getItem(slot);
            if (!inSlot.isEmpty() && ItemStack.isSameItemSameComponents(inSlot, stack)) {
                total += inSlot.getCount();
            }
        }
        return total;
    }

    private void removeFromInventory(ItemStack stack, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < this.player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack inSlot = this.player.getInventory().getItem(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, stack)) {
                continue;
            }
            int taken = Math.min(remaining, inSlot.getCount());
            inSlot.shrink(taken);
            remaining -= taken;
        }
        this.player.getInventory().setChanged();
    }
}
