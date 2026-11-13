package dev.lanhuo.anvilautotree.net;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

public enum StorageKind {
    HYPERDIMENSION(1, 1024),
    SHULKER(12, 64),
    LARGE_CRATE(144, 1);

    public static final StreamCodec<ByteBuf, StorageKind> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
        index -> index >= 0 && index < values().length ? values()[index] : HYPERDIMENSION,
        StorageKind::ordinal
    );

    private final int divisor;

    private final int maxOrderQuantity;

    StorageKind(int divisor, int maxOrderQuantity) {
        this.divisor = divisor;
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public int craftsPerTick(int baseParallelism) {
        return Math.max(1, baseParallelism / divisor);
    }

    public boolean supportsBatch() {
        return this.maxOrderQuantity > 1;
    }

    public int maxOrderQuantity() {
        return this.maxOrderQuantity;
    }
}
