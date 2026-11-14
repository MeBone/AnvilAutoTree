package dev.lanhuo.anvilautotree.craft;

import dev.lanhuo.anvilautotree.AutoCraftConfig;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 后台合成作业队列。
 *
 * <p>每个作业每 tick 的并行次数由 {@link #parallelismFor(ServerPlayer)} 决定，默认取配置
 * {@code parallel}（1024）。后续若要加入「提高并行度」的物品或机制，调用
 * {@link #setParallelismSource(ParallelismSource)} 即可接管该数值，无需改动作业执行逻辑。</p>
 */
public final class CraftJobManager {
    /**
     * 并行度来源，参数为该作业所属玩家。
     */
    @FunctionalInterface
    public interface ParallelismSource {
        int parallelismFor(ServerPlayer player);
    }

    private static final List<CraftJob> JOBS = new ArrayList<>();
    private static ParallelismSource parallelismSource = player -> AutoCraftConfig.PARALLEL.get();

    private CraftJobManager() {
    }

    /**
     * 替换并行度来源，供后续扩展物品/机制使用。
     */
    public static void setParallelismSource(ParallelismSource source) {
        parallelismSource = source == null ? player -> AutoCraftConfig.PARALLEL.get() : source;
    }

    public static int parallelismFor(ServerPlayer player) {
        return Math.max(1, parallelismSource.parallelismFor(player));
    }

    public static void submit(CraftJob job) {
        JOBS.add(job);
    }

    public static void clear() {
        JOBS.forEach(CraftJob::storePendingOutput);
        JOBS.clear();
    }

    public static int size() {
        return JOBS.size();
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        Iterator<CraftJob> iterator = JOBS.iterator();
        while (iterator.hasNext()) {
            CraftJob job = iterator.next();
            if (job.player().isRemoved()) {
                // 玩家已离线：直接回收，避免对失效实体继续扣料/发包
                job.storePendingOutput();
                iterator.remove();
                continue;
            }
            try {
                job.tick(job.craftsPerTick(parallelismFor(job.player())));
            } catch (RuntimeException exception) {
                // 单个作业出错不能把服务端 tick 带崩
                AnvilAutoTree.LOGGER.error("后台合成作业执行失败", exception);
                job.abort();
            }
            if (job.isFinished()) {
                iterator.remove();
                job.complete();
            }
        }
    }
}
