package dev.lanhuo.anvilautotree.mixin;

import dev.lanhuo.anvilautotree.compat.StorageRecipeTransfer;
import java.util.Optional;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "mezz.jei.library.recipes.RecipeTransferManager", remap = false)
abstract class JeiRecipeTransferManagerMixin {
    @Inject(method = "getRecipeTransferHandler", at = @At("RETURN"), cancellable = true, require = 0)
    private <C extends AbstractContainerMenu, R> void anvilautotree$wrapStorageTransfer(
        C menu, IRecipeCategory<R> category, CallbackInfoReturnable<Optional<IRecipeTransferHandler<C, R>>> callback
    ) {
        var result = callback.getReturnValue();
        if (result.isEmpty()) {
            return;
        }
        var handler = result.get();
        var wrapped = StorageRecipeTransfer.wrap(menu, category.getRecipeType(), handler);
        if (wrapped != handler) {
            callback.setReturnValue(Optional.of(wrapped));
        }
    }
}
