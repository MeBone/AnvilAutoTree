package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class ItemHandlerStorageAccess implements StorageAccess {
    private final UnlimitedItemStacksResourceHandler handler;

    public ItemHandlerStorageAccess(UnlimitedItemStacksResourceHandler handler) {
        this.handler = handler;
    }

    @Override
    public long count(ItemStack proto) {
        return handler.count(ItemResource.of(proto));
    }

    @Override
    public int extract(ItemStack proto, int amount, boolean simulate) {
        try (var transaction = Transaction.openRoot()) {
            int extracted = handler.extract(ItemResource.of(proto), amount, transaction);
            if (!simulate) transaction.commit();
            return extracted;
        }
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        try (var transaction = Transaction.openRoot()) {
            int inserted = handler.insert(ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
            return stack.copyWithCount(stack.getCount() - inserted);
        }
    }
}
