package dev.lanhuo.anvilautotree.storage;

import com.mojang.datafixers.util.Either;
import dev.dubhe.anvilcraft.api.TerminalSessions;
import dev.dubhe.anvilcraft.block.entity.storage.StorageBlockEntity;
import dev.dubhe.anvilcraft.saved.storage.LargeCrateStorage;
import dev.lanhuo.anvilautotree.net.StorageKind;
import dev.dubhe.anvilcraft.item.HyperdimensionTerminalItem;
import dev.dubhe.anvilcraft.item.TerminalItem;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.saved.storage.HyperdimensionStorage;
import dev.dubhe.anvilcraft.saved.storage.ShulkerContainerStorage;
import dev.dubhe.anvilcraft.saved.storage.Storages;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class StorageLookup {
    private StorageLookup() {
    }

    public static Optional<BaseStorage<?>> resolve(ServerPlayer player, BlockPos sourcePos) {
        long position = sourcePos.asLong();
        if (TerminalSessions.contains(player.getUUID(), position)) {
            return Optional.<BaseStorage<?>>ofNullable(TerminalSessions.storage(player, position))
                .filter(storage -> kindOf(storage).isPresent());
        }

        if (player.level().hasChunkAt(sourcePos) && player.isWithinBlockInteractionRange(sourcePos, 4.0)
            && player.level().getBlockEntity(sourcePos) instanceof StorageBlockEntity container
            && container.getId() != null) {
            return Storages.get().get(container.getId()).filter(storage -> kindOf(storage).isPresent());
        }
        return Optional.empty();
    }

    public static Optional<BaseStorage<?>> resolve(ServerPlayer player, Either<BlockPos, UUID> source) {
        return source.map(pos -> resolve(player, pos), id -> resolveBoundHyperdimension(player, id));
    }

    private static Optional<BaseStorage<?>> resolveBoundHyperdimension(ServerPlayer player, UUID id) {
        boolean bound = TerminalItem.getAll(player).stream()
            .anyMatch(stack -> stack.getItem() instanceof HyperdimensionTerminalItem
                && id.equals(((TerminalItem) stack.getItem()).targetId(player, stack)));
        return bound ? Storages.get().get(id).filter(HyperdimensionStorage.class::isInstance) : Optional.empty();
    }

    public static Optional<StorageKind> kindOf(BaseStorage<?> storage) {
        return switch (storage) {
            case HyperdimensionStorage ignored -> Optional.of(StorageKind.HYPERDIMENSION);
            case ShulkerContainerStorage ignored -> Optional.of(StorageKind.SHULKER);
            case LargeCrateStorage ignored -> Optional.of(StorageKind.LARGE_CRATE);
            default -> Optional.empty();
        };
    }
}
