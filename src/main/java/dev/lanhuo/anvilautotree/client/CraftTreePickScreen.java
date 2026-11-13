package dev.lanhuo.anvilautotree.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CraftTreePickScreen extends Screen {
    CraftTreePickScreen() {
        super(Component.translatable("anvilautotree.layout.title"));
    }

    @Override
    public void removed() {
        AutoCraftClient.onPickScreenClosed();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
