package dev.lanhuo.anvilautotree.compat;

import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.client.AutoCraftClient;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

/** Uses the recipe handler selected by JEI, including its native inventory and grid semantics. */
public final class StorageRecipeTransfer<C extends AbstractContainerMenu, R> implements IRecipeTransferHandler<C, R> {
    private static final ClassValue<Optional<Method>> SOURCE_POSITION = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> menuType) {
            var anvilcraft = ModList.get().getModFileById("anvilcraft");
            if (anvilcraft == null || !Files.isRegularFile(
                anvilcraft.getFile().findResource(menuType.getName().replace('.', '/') + ".class"))) {
                return Optional.empty();
            }
            for (String name : new String[]{"getSourcePos", "sourcePos"}) {
                try {
                    Method method = menuType.getMethod(name);
                    if (method.getReturnType() == BlockPos.class && !Modifier.isStatic(method.getModifiers())) {
                        return Optional.of(method);
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the other accessor convention.
                }
            }
            return Optional.empty();
        }
    };

    private final IRecipeTransferHandler<C, R> delegate;
    private final BlockPos sourcePos;

    private StorageRecipeTransfer(IRecipeTransferHandler<C, R> delegate, BlockPos sourcePos) {
        this.delegate = delegate;
        this.sourcePos = sourcePos;
    }

    public static <C extends AbstractContainerMenu, R> IRecipeTransferHandler<C, R> wrap(
        C menu, RecipeType<R> recipeType, IRecipeTransferHandler<C, R> handler
    ) {
        if (!recipeType.equals(RecipeTypes.CRAFTING) && !recipeType.equals(RecipeTypes.STONECUTTING)) {
            return handler;
        }
        var accessor = SOURCE_POSITION.get(menu.getClass());
        if (accessor.isEmpty()) {
            return handler;
        }
        try {
            if (accessor.get().invoke(menu) instanceof BlockPos sourcePos) {
                return new StorageRecipeTransfer<>(handler, sourcePos.immutable());
            }
        } catch (ReflectiveOperationException exception) {
            AnvilAutoTree.LOGGER.debug("Unable to read the AnvilCraft menu's storage position", exception);
        }
        return handler;
    }

    @Override
    public Class<? extends C> getContainerClass() {
        return delegate.getContainerClass();
    }

    @Override
    public Optional<MenuType<C>> getMenuType() {
        return delegate.getMenuType();
    }

    @Override
    public RecipeType<R> getRecipeType() {
        return delegate.getRecipeType();
    }

    @Override
    public IRecipeTransferError transferRecipe(
        C menu, R recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer
    ) {
        if (AutoCraftJeiPlugin.hasRecipeButton() || !(recipe instanceof RecipeHolder<?> holder)) {
            return delegate.transferRecipe(menu, recipe, slots, player, maxTransfer, doTransfer);
        }
        var check = delegate.transferRecipe(menu, recipe, slots, player, maxTransfer, false);
        boolean missing = check != null && check.getType() == IRecipeTransferError.Type.USER_FACING;
        if (missing || (doTransfer && check == null && Screen.hasAltDown())) {
            if (doTransfer) {
                plan(menu, recipe, slots, player, maxTransfer, holder);
            }
            return AutoCraftClient.missingIngredients();
        }
        return doTransfer ? delegate.transferRecipe(menu, recipe, slots, player, maxTransfer, true) : check;
    }

    boolean canPlan(C menu, R recipe, IRecipeSlotsView slots, Player player) {
        var check = delegate.transferRecipe(menu, recipe, slots, player, false, false);
        return check == null || check.getType() != IRecipeTransferError.Type.INTERNAL;
    }

    void startPlan(C menu, R recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer) {
        if (recipe instanceof RecipeHolder<?> holder) {
            plan(menu, recipe, slots, player, maxTransfer, holder);
        }
    }

    private void plan(C menu, R recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, RecipeHolder<?> holder) {
        AutoCraftClient.plan(menu, sourcePos, holder, player,
            who -> delegate.transferRecipe(menu, recipe, slots, who, maxTransfer, true));
    }
}
