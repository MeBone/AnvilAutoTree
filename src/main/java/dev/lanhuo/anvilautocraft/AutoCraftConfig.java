package dev.lanhuo.anvilautocraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置。
 *
 * <p>{@code parallel} 等价于后台模拟合成面板的并行度：每个服务端 tick 最多推进多少次合成。
 * 后续若要由物品或机制调整并行度，可在此值之上叠加运行时倍率。</p>
 */
public final class AutoCraftConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue PARALLEL;
    public static final ModConfigSpec.IntValue MAX_DEPTH;
    public static final ModConfigSpec.IntValue MAX_STEPS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        PARALLEL = builder
            .comment("超维存储每个服务端 tick 最多执行的合成次数。潜影集装箱使用该值的 1/12（向下取整，最少 1）。")
            .defineInRange("parallel", 1024, 1, 1_000_000);
        MAX_DEPTH = builder
            .comment("合成链的最大递归深度，超过即判定为过于复杂的依赖树。")
            .defineInRange("maxDepth", 16, 1, 64);
        MAX_STEPS = builder
            .comment("单个合成计划允许的最大步骤数。")
            .defineInRange("maxSteps", 256, 1, 4096);
        SPEC = builder.build();
    }

    private AutoCraftConfig() {
    }
}
