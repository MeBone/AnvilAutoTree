package dev.lanhuo.anvilautotree.client;

import dev.dubhe.anvilcraft.client.gui.screen.StorageScreen;
import dev.lanhuo.anvilautotree.AnvilAutoTree;

final class StorageScreenAccess {
    private StorageScreenAccess() {}

    static void showCrafting(StorageScreen screen) {
        try {
            var show = StorageScreen.class.getDeclaredMethod("showCraftingAfterTransfer");
            show.setAccessible(true);
            show.invoke(screen);
        } catch (ReflectiveOperationException exception) {
            AnvilAutoTree.LOGGER.debug("Unable to open AnvilCraft crafting panel", exception);
        }
    }
}
