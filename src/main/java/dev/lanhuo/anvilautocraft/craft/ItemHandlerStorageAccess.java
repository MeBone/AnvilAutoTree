package dev.lanhuo.anvilautocraft.craft;

import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import net.minecraft.world.item.ItemStack;

/**
 * 直接读写超维存储和潜影集装箱共用的物品处理器。
 *
 * <p>处理器在修改后会触发 {@code onContentsChanged}，进而保存存档并同步终端界面，
 * 因此这里不需要额外发同步包。</p>
 */
public final class ItemHandlerStorageAccess implements StorageAccess {
    private final UnlimitedItemStacksResourceHandler handler;

    public ItemHandlerStorageAccess(UnlimitedItemStacksResourceHandler handler) {
        this.handler = handler;
    }

    @Override
    public long count(ItemStack proto) {
        long total = 0L;
        int slots = this.handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = this.handler.getStackInSlot(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, proto)) {
                continue;
            }
            total += Math.max(0L, this.handler.getAmountAsLong(slot));
        }
        return total;
    }

    @Override
    public int extract(ItemStack proto, int amount, boolean simulate) {
        if (simulate) {
            return (int) Math.min(amount, this.count(proto));
        }
        int remaining = amount;
        int slots = this.handler.getSlots();
        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            ItemStack stack = this.handler.getStackInSlot(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, proto)) {
                continue;
            }
            int available = (int) Math.min(remaining, this.handler.getAmountAsLong(slot));
            while (available > 0) {
                int take = Math.min(available, stack.getMaxStackSize());
                ItemStack extracted = this.handler.extractItem(slot, take, false);
                if (extracted.isEmpty()) {
                    break;
                }
                remaining -= extracted.getCount();
                available -= extracted.getCount();
                if (extracted.getCount() < take) {
                    break;
                }
            }
        }
        return amount - remaining;
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        return this.handler.insertItem(stack, false);
    }
}
