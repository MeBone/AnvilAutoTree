package dev.lanhuo.anvilautotree.net;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

public enum StorageKind {
    HYPERDIMENSION(1),
    SHULKER(12),
    LARGE_CRATE(144);

    public static final StreamCodec<ByteBuf, StorageKind> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
        index -> index >= 0 && index < values().length ? values()[index] : HYPERDIMENSION,
        StorageKind::ordinal
    );

    private final int divisor;

    StorageKind(int divisor) {
        this.divisor = divisor;
    }

    public int craftsPerTick(int baseParallelism) {
        return Math.max(1, baseParallelism / divisor);
    }

    public boolean supportsBatch() {
        return this != LARGE_CRATE;
    }
}
