package dev.lanhuo.anvilautotree.client;

import dev.lanhuo.anvilautotree.craft.RecipeData;
import dev.dubhe.anvilcraft.recipe.sync.RecipesRecord;
import dev.lanhuo.anvilautotree.craft.RecipePreference;
import dev.lanhuo.anvilautotree.net.CraftNodePayload;
import java.util.ArrayList;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

final class RecipeChoicesWindow {
    private static final int ROW_HEIGHT = 22;
    private final List<Choice> choices;
    private final AnvilLibWindow renderer;
    private Rect2i bounds;
    private int offset;
    private int rows;
    private int screenWidth;
    private int screenHeight;

    private RecipeChoicesWindow(List<Choice> choices) {
        this.choices = choices;
        renderer = ModList.get().isLoaded("anvillib_ui") ? new AnvilLibWindow(Math.min(8, choices.size())) : null;
    }

    static RecipeChoicesWindow open(CraftNodePayload parent, CraftNodePayload child) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var manager = RecipesRecord.getRecipes(level);
        if (manager == null) return null;
        var preferences = AutoCraftClientConfig.recipePreferences();
        var choices = new ArrayList<Choice>();
        var parentRecipe = parent.recipeId().map(RecipeData::key).map(manager::byKey).orElse(null);
        if (parentRecipe != null && RecipePreference.supports(parentRecipe.value())) {
            var ingredients = RecipeData.ingredients(parentRecipe.value());
            for (int slot = 0; slot < ingredients.size(); slot++) {
                var options = new LinkedHashMap<Identifier, ItemStack>();
                for (var item : ingredients.get(slot).stream().flatMap(ingredient -> RecipeData.options(ingredient, level).stream()).toList()) {
                    options.putIfAbsent(RecipePreference.item(item), item);
                }
                if (options.size() < 2 || !options.containsKey(RecipePreference.item(child.stack()))) continue;
                String key = RecipePreference.ingredient(parentRecipe.id().identifier(), slot);
                Component group = Component.translatable("anvilautotree.alternatives.slot", parent.stack().getHoverName(), slot + 1);
                choices.add(automatic(key, group, preferences.get(key)));
                options.forEach((id, stack) -> choices.add(new Choice(key, id, List.of(stack),
                    stack.getHoverName(), group, id.equals(preferences.get(key)))));
            }
        }

        addRecipes(choices, child, manager.values(), level, preferences);
        if (parent.parent() < 0) addRecipes(choices, parent, manager.values(), level, preferences);
        return choices.isEmpty() ? null : new RecipeChoicesWindow(choices);
    }

    private static void addRecipes(List<Choice> choices, CraftNodePayload node, java.util.Collection<RecipeHolder<?>> recipes,
                                   net.minecraft.world.level.Level level,
                                   java.util.Map<String, Identifier> preferences) {
        var matches = recipes.stream()
            .filter(holder -> RecipePreference.supports(holder.value()) && ItemStack.isSameItemSameComponents(
                RecipeData.result(holder.value(), level), node.stack()))
            .sorted(java.util.Comparator.comparing(RecipeHolder::id)).toList();
        if (matches.size() < 2) return;
        String key = RecipePreference.recipe(node.stack());
        Component group = Component.translatable("anvilautotree.alternatives.output", node.stack().getHoverName());
        choices.add(automatic(key, group, preferences.get(key)));
        for (var holder : matches) {
            var inputs = RecipeData.ingredients(holder.value()).stream()
                .flatMap(Optional::stream)
                .flatMap(ingredient -> RecipeData.options(ingredient, level).stream().limit(1)).toList();
            var label = inputs.isEmpty() ? Component.literal(holder.id().identifier().toString()) : inputs.getFirst().getHoverName();
            choices.add(new Choice(key, holder.id().identifier(), inputs, label,
                group.copy().append(" · ").append(holder.id().identifier().toString()), holder.id().identifier().equals(preferences.get(key))));
        }
    }

    private static Choice automatic(String key, Component group, Identifier selected) {
        return new Choice(key, null, List.of(), Component.translatable("anvilautotree.alternatives.automatic"), group, selected == null);
    }

    private void layout(int width, int height) {
        if (bounds != null && width == screenWidth && height == screenHeight) return;
        screenWidth = width;
        screenHeight = height;
        rows = Math.min(Math.min(8, choices.size()), Math.max(1, (height - 50) / ROW_HEIGHT));
        offset = Math.min(offset, choices.size() - rows);
        int windowWidth = Math.min(252, width - 12);
        int windowHeight = 39 + rows * ROW_HEIGHT;
        bounds = new Rect2i((width - windowWidth) / 2, (height - windowHeight) / 2, windowWidth, windowHeight);
        if (renderer != null) {
            renderer.layoutFrame(bounds.getX(), bounds.getY(), windowWidth, windowHeight);
            for (int index = 0; index < rows; index++) renderer.layoutButton(index, row(index));
        }
    }

    private Rect2i row(int index) {
        return new Rect2i(bounds.getX() + 6, bounds.getY() + 21 + index * ROW_HEIGHT, bounds.getWidth() - 12, ROW_HEIGHT - 2);
    }

    private static boolean inside(double x, double y, Rect2i rectangle) {
        return x >= rectangle.getX() && x < rectangle.getX() + rectangle.getWidth()
            && y >= rectangle.getY() && y < rectangle.getY() + rectangle.getHeight();
    }

    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var mc = Minecraft.getInstance();
        layout(mc.screen.width, mc.screen.height);
        var font = mc.font;
        graphics.nextStratum();
        graphics.fill(0, 0, screenWidth, screenHeight, 0x55000000);
        if (renderer != null) renderer.render(graphics);
        else graphics.fill(bounds.getX(), bounds.getY(), bounds.getX() + bounds.getWidth(),
            bounds.getY() + bounds.getHeight(), 0xFFBDBDBD);
        graphics.centeredText(font, Component.translatable("anvilautotree.alternatives.title"),
            screenWidth / 2, bounds.getY() + 7, 0xFFFFFFFF);
        Choice hovered = null;
        for (int index = 0; index < rows; index++) {
            var choice = choices.get(offset + index);
            var row = row(index);
            boolean over = inside(mouseX, mouseY, row);
            if (renderer != null) renderer.renderButton(index, graphics, true, over, choice.selected());
            else graphics.fill(row.getX(), row.getY(), row.getX() + row.getWidth(), row.getY() + row.getHeight(),
                choice.selected() ? 0xFF9393B0 : over ? 0xFFDDDDDD : 0xFFC6C6C6);
            int x = row.getX() + 4;
            for (int slot = 0; slot < Math.min(4, choice.items().size()); slot++) {
                graphics.item(choice.items().get(slot), x, row.getY() + 2);
                x += 17;
            }
            int width = row.getX() + row.getWidth() - x - 6;
            graphics.text(font, font.plainSubstrByWidth(choice.label().getString(), width),
                x, row.getY() + 6, 0xFF202020, false);
            if (over) hovered = choice;
        }
        graphics.centeredText(font, Component.translatable("anvilautotree.alternatives.footer",
            offset + 1, offset + rows, choices.size()), screenWidth / 2,
            bounds.getY() + bounds.getHeight() - 12, 0xFFFFFFFF);
        if (hovered != null) graphics.setTooltipForNextFrame(font, hovered.group(), mouseX, mouseY);
    }

    boolean click(double x, double y) {
        if (bounds == null || !inside(x, y, bounds)) return true;
        for (int index = 0; index < rows; index++) {
            if (inside(x, y, row(index))) {
                var choice = choices.get(offset + index);
                AutoCraftClient.selectRecipePreference(choice.key(), choice.id());
                return true;
            }
        }
        return false;
    }

    void scroll(double delta) {
        offset = Math.clamp(offset - (int) Math.signum(delta), 0, Math.max(0, choices.size() - rows));
    }

    private record Choice(String key, Identifier id, List<ItemStack> items, Component label, Component group,
                          boolean selected) {}
}
