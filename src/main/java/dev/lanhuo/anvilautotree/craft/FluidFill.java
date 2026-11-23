package dev.lanhuo.anvilautotree.craft;

import dev.dubhe.anvilcraft.api.StoragePortManager;
import dev.dubhe.anvilcraft.api.fluid.FluidHandlerWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.FluidType;

record FluidFill(UUID storage, FluidStack fluid, ItemStack container, ItemStack result, int available) {
    static List<FluidFill> options(UUID storage) {
        if (storage == null) return List.of();
        var options = new ArrayList<FluidFill>();
        for (var entry : StoragePortManager.collect(storage)) {
            var bucket = entry.icon().copyWithAmount(FluidType.BUCKET_VOLUME);
            var filled = FluidUtil.getFilledBucket(bucket);
            if (!filled.isEmpty() && filled.getCraftingRemainingItem().is(Items.BUCKET)) {
                options.add(new FluidFill(storage, bucket, Items.BUCKET.getDefaultInstance(), filled, entry.amount()));
            }
            var bottle = Items.GLASS_BOTTLE.getDefaultInstance();
            var dose = bucket.copyWithAmount(FluidType.BUCKET_VOLUME / 4);
            var bottled = FluidHandlerWrapper.determineFilledContainer(dose, bottle);
            if (!bottled.isEmpty()) options.add(new FluidFill(storage, dose, bottle, bottled, entry.amount()));
        }
        return options;
    }

    boolean drain(int crafts) {
        int amount = Math.multiplyExact(fluid.getAmount(), crafts);
        return StoragePortManager.drain(storage, fluid, amount, true) == amount
            && StoragePortManager.drain(storage, fluid, amount) == amount;
    }
}
