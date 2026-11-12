package dev.lanhuo.anvilautocraft.craft;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 一次自动合成的规划结果。
 *
 * <p>{@link #supported()} 为 {@code false} 表示目标配方本身超出支持范围（非工作台/切石机配方）；
 * {@link #errors()} 非空表示依赖链无法满足，例如缺少基础材料或检测到自循环配方。</p>
 */
public final class CraftPlan {
    private final List<CraftStep> steps;
    private final List<Component> preview;
    private final List<Component> errors;
    private final boolean supported;
    private final CraftTreeNode tree;

    public CraftPlan(
        List<CraftStep> steps,
        List<Component> preview,
        List<Component> errors,
        boolean supported,
        CraftTreeNode tree
    ) {
        this.steps = List.copyOf(steps);
        this.preview = List.copyOf(preview);
        this.errors = List.copyOf(errors);
        this.supported = supported;
        this.tree = tree;
    }

    public static CraftPlan unsupported(List<Component> preview) {
        return new CraftPlan(
            List.of(),
            preview,
            List.of(Component.translatable("anvilautocraft.error.unsupported_recipe")),
            false,
            null
        );
    }

    public boolean isSuccess() {
        return this.supported && this.errors.isEmpty();
    }

    public List<CraftStep> steps() {
        return this.steps;
    }

    public List<Component> preview() {
        return this.preview;
    }

    public List<Component> errors() {
        return this.errors;
    }

    public boolean supported() {
        return this.supported;
    }

    /**
     * 目标产物的合成树；配方不受支持时为 {@code null}。
     */
    public CraftTreeNode tree() {
        return this.tree;
    }

    public int totalCrafts() {
        int total = 0;
        for (CraftStep step : this.steps) {
            total += step.crafts();
        }
        return total;
    }
}
