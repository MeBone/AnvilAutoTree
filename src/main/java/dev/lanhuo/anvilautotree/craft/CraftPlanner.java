package dev.lanhuo.anvilautotree.craft;

import dev.lanhuo.anvilautotree.AutoCraftConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * 合成链规划器。
 *
 * <p>从「终端合成格要放入的目标配方原料」出发，递归地把缺口拆成子配方，产出按「子步骤在前」
 * 排列的批量步骤列表。规划只读存储，不改动任何状态。</p>
 *
 * <p>自循环防护由 {@link #path} 承担：某个物品一旦出现在当前递归链上，再次需要它时该候选配方
 * 立即失败并回滚，改试其他候选；全部候选都失败则上报缺口。因此「铁块拆铁锭、铁锭合成铁块」
 * 这类互为原料的配方不会形成无限递归。</p>
 */
public final class CraftPlanner {
    private static final int MAX_CRAFTS_PER_STEP = 100_000;

    private final ServerLevel level;
    private final StorageAccess storage;
    private final Map<StackKey, Long> storedCounts = new HashMap<>();
    private final Map<StackKey, Long> produced = new HashMap<>();
    private final Map<StackKey, Long> reserved = new HashMap<>();
    private final Map<StackKey, List<Candidate>> candidateCache = new HashMap<>();
    private final List<CraftStep> steps = new ArrayList<>();
    private final Deque<StackKey> path = new ArrayDeque<>();

    private CraftPlanner(ServerLevel level, StorageAccess storage) {
        this.level = level;
        this.storage = storage;
    }

    public static CraftPlan plan(ServerLevel level, StorageAccess storage, RecipeHolder<?> target, boolean stonecutter, int quantity) {
        CraftPlanner planner = new CraftPlanner(level, storage);
        ItemStack output = target.value().getResultItem(level.registryAccess());
        List<Component> preview = new ArrayList<>();
        preview.add(Component.translatable("anvilautotree.preview.target", output.getHoverName()));
        List<IngredientChoice> root = planner.ingredientsOf(target.value(), stonecutter, false);
        if (root.isEmpty() || output.isEmpty()) {
            return CraftPlan.unsupported(preview);
        }
        if (quantity > 1) {
            output = planner.batchOutput(target.value(), root);
            if (output.isEmpty()) {
                return new CraftPlan(List.of(), preview,
                    List.of(Component.translatable("anvilautotree.error.unsupported_batch")), false, null);
            }
        }
        List<Component> errors = new ArrayList<>();
        List<CraftTreeNode> rootChildren = new ArrayList<>();
        for (Map.Entry<StackKey, Integer> entry : aggregate(root).entrySet()) {
            StackKey key = entry.getKey();
            long required = (long) entry.getValue() * quantity;
            long available = planner.available(key);
            if (available < required) {
                preview.add(Component.translatable(
                    "anvilautotree.preview.missing",
                    key.proto().getHoverName(),
                    required - available
                ));
            }
            try {
                rootChildren.add(planner.ensure(key, required));
                planner.reserved.merge(key, required, Long::sum);
            } catch (MissingRecipeException exception) {
                errors.add(exception.toComponent());
                rootChildren.add(exception.missingNode());
            } catch (TooComplexException exception) {
                errors.add(Component.translatable("anvilautotree.error.too_complex"));
                break;
            }
        }
        if (quantity > 1 && errors.isEmpty()) {
            if (planner.steps.size() >= AutoCraftConfig.MAX_STEPS.get()) {
                errors.add(Component.translatable("anvilautotree.error.too_complex"));
            } else {
                planner.steps.add(new CraftStep(root, output, quantity, stonecutter, target.id()));
            }
        }
        for (CraftStep step : planner.steps) {
            preview.add(Component.translatable(
                "anvilautotree.preview.craft",
                step.output().getHoverName(),
                step.totalOutput().getCount(),
                step.crafts()
            ));
        }
        CraftTreeNode tree = new CraftTreeNode(
            output.copyWithCount(1),
            (long) output.getCount() * quantity,
            planner.initialCount(StackKey.of(output)),
            quantity > 1 ? quantity : 0,
            false,
            rootChildren
        );
        return new CraftPlan(planner.steps, preview, errors, true, tree);
    }

    private ItemStack batchOutput(Recipe<?> recipe, List<IngredientChoice> choices) {
        if (recipe.getClass() == StonecutterRecipe.class && choices.size() == 1) {
            return assembleBatch((StonecutterRecipe) recipe, new SingleRecipeInput(choices.getFirst().key().proto()));
        }
        // Custom recipes can depend on state or consume/return ingredients differently.
        if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) {
            return ItemStack.EMPTY;
        }
        var crafting = (CraftingRecipe) recipe;
        int width = crafting instanceof ShapedRecipe shaped ? shaped.getWidth() : Math.min(3, choices.size());
        int height = crafting instanceof ShapedRecipe shaped ? shaped.getHeight() : Math.ceilDiv(choices.size(), width);
        var ingredients = crafting.getIngredients();
        var stacks = new ArrayList<ItemStack>(width * height);
        var chosen = choices.iterator();
        for (int slot = 0; slot < width * height; slot++) {
            stacks.add(slot < ingredients.size() && !ingredients.get(slot).isEmpty()
                ? chosen.next().key().proto() : ItemStack.EMPTY);
        }
        return assembleBatch(crafting, CraftingInput.of(width, height, stacks));
    }

    private <T extends RecipeInput> ItemStack assembleBatch(Recipe<T> recipe, T input) {
        if (!recipe.matches(input, this.level) || recipe.getRemainingItems(input).stream().anyMatch(stack -> !stack.isEmpty())) {
            return ItemStack.EMPTY;
        }
        return recipe.assemble(input, this.level.registryAccess());
    }

    /**
     * 把「每个槽位一条原料」合并成「每种物品每轮合成需要多少」，避免同一物品占多个槽时缺口被重复计算。
     */
    private static Map<StackKey, Integer> aggregate(List<IngredientChoice> ingredients) {
        Map<StackKey, Integer> totals = new java.util.LinkedHashMap<>();
        for (IngredientChoice ingredient : ingredients) {
            totals.merge(ingredient.key(), ingredient.perCraft(), Integer::sum);
        }
        return totals;
    }

    private CraftTreeNode ensure(StackKey key, long required) {
        long available = this.available(key);
        long stored = this.initialCount(key);
        if (available >= required) {
            return CraftTreeNode.leaf(key.proto(), required, stored);
        }
        return this.craft(key, required, required - available, stored);
    }

    private CraftTreeNode craft(StackKey key, long required, long missing, long stored) {
        if (this.path.contains(key)) {
            throw MissingRecipeException.leaf(key, true, required, stored);
        }
        if (this.path.size() >= AutoCraftConfig.MAX_DEPTH.get()) {
            throw new TooComplexException();
        }
        if (this.steps.size() >= AutoCraftConfig.MAX_STEPS.get()) {
            throw new TooComplexException();
        }
        this.path.addLast(key);
        try {
            List<Candidate> candidates = this.candidatesOf(key);
            if (candidates.isEmpty()) {
                throw MissingRecipeException.leaf(key, false, required, stored);
            }
            MissingRecipeException failure = null;
            for (Candidate candidate : candidates) {
                Snapshot snapshot = this.snapshot();
                try {
                    long crafts = (missing + candidate.output().getCount() - 1L) / candidate.output().getCount();
                    if (crafts <= 0 || crafts > MAX_CRAFTS_PER_STEP) {
                        throw new TooComplexException();
                    }
                    List<CraftTreeNode> children = new ArrayList<>();
                    for (Map.Entry<StackKey, Integer> entry : aggregate(candidate.ingredients()).entrySet()) {
                        long need = (long) entry.getValue() * crafts;
                        children.add(this.ensure(entry.getKey(), need));
                        this.reserved.merge(entry.getKey(), need, Long::sum);
                    }
                    this.produced.merge(key, candidate.output().getCount() * crafts, Long::sum);
                    this.steps.add(new CraftStep(
                        candidate.ingredients(),
                        candidate.output(),
                        (int) crafts,
                        candidate.stonecutter(),
                        candidate.holder().id()
                    ));
                    return new CraftTreeNode(key.proto(), required, stored, (int) crafts, false, children);
                } catch (MissingRecipeException exception) {
                    this.restore(snapshot);
                    failure = exception.under(key, required, stored);
                }
            }
            throw failure != null ? failure : MissingRecipeException.leaf(key, false, required, stored);
        } finally {
            this.path.removeLast();
        }
    }

    private long available(StackKey key) {
        long stored = this.initialCount(key);
        long extra = this.produced.getOrDefault(key, 0L);
        long used = this.reserved.getOrDefault(key, 0L);
        return stored + extra - used;
    }

    private long initialCount(StackKey key) {
        return this.storedCounts.computeIfAbsent(key, candidate -> this.storage.count(candidate.proto()));
    }

    private List<Candidate> candidatesOf(StackKey key) {
        return this.candidateCache.computeIfAbsent(key, this::findCandidates);
    }

    private List<Candidate> findCandidates(StackKey key) {
        List<Candidate> found = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : this.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack output = holder.value().getResultItem(this.level.registryAccess());
            if (output.isEmpty() || !ItemStack.isSameItemSameComponents(output, key.proto())) {
                continue;
            }
            List<IngredientChoice> ingredients = this.ingredientsOf(holder.value(), false, true);
            if (ingredients.isEmpty()) {
                continue;
            }
            found.add(new Candidate(holder, ingredients, output.copy(), false));
        }
        for (RecipeHolder<StonecutterRecipe> holder : this.level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING)) {
            ItemStack output = holder.value().getResultItem(this.level.registryAccess());
            if (output.isEmpty() || !ItemStack.isSameItemSameComponents(output, key.proto())) {
                continue;
            }
            List<IngredientChoice> ingredients = this.ingredientsOf(holder.value(), true, true);
            if (ingredients.isEmpty()) {
                continue;
            }
            found.add(new Candidate(holder, ingredients, output.copy(), true));
        }
        found.sort(Comparator.comparingInt(candidate -> candidate.ingredients().size()));
        return List.copyOf(found);
    }

    /**
     * 解析配方原料；返回空列表表示该配方不在支持范围内。
     *
     * @param requireNoRemainder 为 {@code true} 时拒绝带容器剩余物的原料（如桶装液体），
     *                           因为后台合成没有容器返还流程
     */
    private List<IngredientChoice> ingredientsOf(Recipe<?> recipe, boolean stonecutter, boolean requireNoRemainder) {
        List<Ingredient> ingredients;
        if (stonecutter) {
            if (!(recipe instanceof StonecutterRecipe)) {
                return List.of();
            }
            ingredients = recipe.getIngredients();
        } else {
            if (!(recipe instanceof CraftingRecipe crafting) || crafting.isSpecial()) {
                return List.of();
            }
            if (crafting instanceof ShapedRecipe shaped && (shaped.getWidth() > 3 || shaped.getHeight() > 3)) {
                return List.of();
            }
            if (crafting instanceof ShapelessRecipe shapeless && shapeless.getIngredients().size() > 9) {
                return List.of();
            }
            ingredients = crafting.getIngredients();
        }
        List<IngredientChoice> result = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] options = ingredient.getItems();
            if (options.length == 0) {
                return List.of();
            }
            ItemStack chosen = this.chooseOption(options, requireNoRemainder);
            if (chosen.isEmpty()) {
                return List.of();
            }
            result.add(new IngredientChoice(StackKey.of(chosen), 1));
        }
        return result;
    }

    private ItemStack chooseOption(ItemStack[] options, boolean requireNoRemainder) {
        ItemStack best = ItemStack.EMPTY;
        long bestAmount = -1L;
        for (ItemStack option : options) {
            if (requireNoRemainder && option.hasCraftingRemainingItem()) {
                continue;
            }
            long amount = this.available(StackKey.of(option));
            if (amount > bestAmount) {
                best = option;
                bestAmount = amount;
            }
        }
        return best;
    }

    private Snapshot snapshot() {
        return new Snapshot(new HashMap<>(this.produced), new HashMap<>(this.reserved), this.steps.size());
    }

    private void restore(Snapshot snapshot) {
        this.produced.clear();
        this.produced.putAll(snapshot.produced());
        this.reserved.clear();
        this.reserved.putAll(snapshot.reserved());
        while (this.steps.size() > snapshot.steps()) {
            this.steps.remove(this.steps.size() - 1);
        }
    }

    private record Snapshot(Map<StackKey, Long> produced, Map<StackKey, Long> reserved, int steps) {
    }

    private record Candidate(RecipeHolder<?> holder, List<IngredientChoice> ingredients, ItemStack output, boolean stonecutter) {
    }

    private static final class TooComplexException extends RuntimeException {
        private TooComplexException() {
            super(null, null, false, false);
        }
    }

    private static final class MissingRecipeException extends RuntimeException {
        private final StackKey key;
        private final boolean cycle;
        private final long required;
        private final long stored;
        /** 从上层物品一路到缺口物品的断链，用于把整条补不齐的分支画进合成树。 */
        private final CraftTreeNode node;

        private MissingRecipeException(StackKey key, boolean cycle, long required, long stored, CraftTreeNode node) {
            super(null, null, false, false);
            this.key = key;
            this.cycle = cycle;
            this.required = required;
            this.stored = stored;
            this.node = node;
        }

        private static MissingRecipeException leaf(StackKey key, boolean cycle, long required, long stored) {
            return new MissingRecipeException(
                key,
                cycle,
                required,
                stored,
                CraftTreeNode.missing(key.proto(), required, stored, List.of())
            );
        }

        /**
         * 失败沿着递归向上传播时，把上层物品也挂进这条断链，玩家在树里能看到「哪个物品的哪条分支补不齐」。
         */
        private MissingRecipeException under(StackKey parentKey, long parentRequired, long parentStored) {
            return new MissingRecipeException(
                this.key,
                this.cycle,
                this.required,
                this.stored,
                CraftTreeNode.missing(parentKey.proto(), parentRequired, parentStored, List.of(this.node))
            );
        }

        private Component toComponent() {
            return Component.translatable(
                this.cycle ? "anvilautotree.error.cycle" : "anvilautotree.error.no_recipe",
                this.key.proto().getHoverName()
            );
        }

        /**
         * 缺口无法补齐时，用红框节点把它画进合成树，玩家不用再看聊天栏文字。
         */
        private CraftTreeNode missingNode() {
            return this.node;
        }
    }
}
