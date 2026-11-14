package dev.lanhuo.anvilautotree.mixin;

import dev.lanhuo.anvilautotree.client.CraftTreeOverlay;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.common.Internal;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.input.handlers.BookmarkInputHandler", remap = false)
abstract class JeiBookmarkInputMixin {
    @Shadow @Final private BookmarkList bookmarkList;

    @Inject(method = "handleUserInput", at = @At("HEAD"), cancellable = true, require = 0)
    private void anvilautotree$bookmarkTreeItem(Screen screen, UserInput input, IInternalKeyMappings keys,
                                               CallbackInfoReturnable<Optional<IUserInputHandler>> callback) {
        if (!input.is(keys.getBookmark())) return;

        var stack = CraftTreeOverlay.LIVE.itemAt(input.getMouseX(), input.getMouseY());
        if (stack.isEmpty()) return;

        // Claim the hovered tree node before JEI queries the storage slots underneath it.
        if (!input.isSimulate()) {
            Internal.getOptionalJeiRuntime().flatMap(runtime ->
                runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack))
                .ifPresent(this.bookmarkList::addIngredientBookmark);
        }
        callback.setReturnValue(Optional.of((IUserInputHandler) (Object) this));
    }
}
