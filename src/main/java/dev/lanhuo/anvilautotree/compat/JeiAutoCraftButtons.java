package dev.lanhuo.anvilautotree.compat;

import java.util.function.Supplier;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;

final class JeiAutoCraftButtons {
    private static final IDrawable PURPLE_PLUS = new IDrawable() {
        @Override
        public int getWidth() {
            return 10;
        }

        @Override
        public int getHeight() {
            return 10;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.fill(x + 3, y, x + 7, y + 10, 0xFF422967);
            graphics.fill(x, y + 3, x + 10, y + 7, 0xFF422967);
            graphics.fill(x + 4, y + 1, x + 6, y + 9, 0xFFCB92FF);
            graphics.fill(x + 1, y + 4, x + 9, y + 6, 0xFFCB92FF);
        }
    };

    private JeiAutoCraftButtons() {
    }

    static void register(IAdvancedRegistration registration, Supplier<IJeiRuntime> runtime) {
        registration.addRecipeButtonFactory(new IRecipeButtonControllerFactory() {
            @Override
            public <R> IIconButtonController createButtonController(IRecipeLayoutDrawable<R> layout) {
                var type = layout.getRecipeCategory().getRecipeType();
                if (!(layout.getRecipe() instanceof RecipeHolder<?>)
                    || (!type.equals(RecipeTypes.CRAFTING) && !type.equals(RecipeTypes.STONECUTTING))) {
                    return null;
                }
                return new AutoCraftButton<>(layout, runtime);
            }
        });
    }

    private record AutoCraftButton<R>(IRecipeLayoutDrawable<R> layout, Supplier<IJeiRuntime> runtime)
        implements IIconButtonController {
        @Override
        public void initState(IButtonState state) {
            state.setIcon(PURPLE_PLUS);
        }

        @Override
        public void updateState(IButtonState state) {
            Target<R> target = target();
            state.setVisible(target != null);
            state.setActive(target != null && target.transfer().canPlan(target.menu(), layout.getRecipe(),
                layout.getRecipeSlotsView(), Minecraft.getInstance().player));
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("anvilautotree.jei.open_tree"));
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            Target<R> target = target();
            var player = Minecraft.getInstance().player;
            if (target == null || player == null
                || !target.transfer().canPlan(target.menu(), layout.getRecipe(), layout.getRecipeSlotsView(), player)) {
                return false;
            }
            if (!input.isSimulate()) {
                target.transfer().startPlan(target.menu(), layout.getRecipe(), layout.getRecipeSlotsView(), player, Screen.hasShiftDown());
                if (runtime.get().getRecipesGui() instanceof Screen recipes && Minecraft.getInstance().screen == recipes) {
                    recipes.onClose();
                }
            }
            return true;
        }

        private Target<R> target() {
            IJeiRuntime jei = runtime.get();
            if (jei == null || Minecraft.getInstance().player == null
                || !(jei.getRecipesGui().getParentScreen().orElse(null) instanceof AbstractContainerScreen<?> screen)) {
                return null;
            }
            AbstractContainerMenu menu = screen.getMenu();
            var handler = jei.getRecipeTransferManager().getRecipeTransferHandler(menu, layout.getRecipeCategory()).orElse(null);
            return handler instanceof StorageRecipeTransfer<AbstractContainerMenu, R> transfer ? new Target<>(menu, transfer) : null;
        }
    }

    private record Target<R>(AbstractContainerMenu menu, StorageRecipeTransfer<AbstractContainerMenu, R> transfer) {
    }
}
