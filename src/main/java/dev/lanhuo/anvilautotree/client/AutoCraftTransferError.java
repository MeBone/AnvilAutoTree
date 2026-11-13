package dev.lanhuo.anvilautotree.client;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.network.chat.Component;

/**
 * 用于接管「+」按钮的伪错误。
 *
 * <p>JEI 只有在错误类型允许转移时才让按钮可点；{@code COSMETIC} 恰好既保持按钮可点，
 * 又只显示提示、不阻止界面交互，因此适合在这里承载「点击后自动补齐」的入口。</p>
 */
public final class AutoCraftTransferError implements IRecipeTransferError {
    private final Component tooltip;

    public AutoCraftTransferError(Component tooltip) {
        this.tooltip = tooltip;
    }

    @Override
    public Type getType() {
        return Type.COSMETIC;
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip) {
        tooltip.add(this.tooltip);
    }
}
