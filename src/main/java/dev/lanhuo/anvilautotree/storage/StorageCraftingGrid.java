package dev.lanhuo.anvilautotree.storage;

import dev.dubhe.anvilcraft.rpc.StorageServerStub;
import dev.dubhe.anvilcraft.saved.storage.CraftingStorage;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;

/** Uses the same crafting-grid target as AnvilCraft's own transfer. */
public final class StorageCraftingGrid {
    private static final Access ACCESS = discover();
    private final Object target;

    private StorageCraftingGrid(Object target) {
        this.target = target;
    }

    public static Optional<StorageCraftingGrid> resolve(ServerPlayer player, BlockPos pos) {
        if (ACCESS == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            var registries = (ThreadLocal<HolderLookup.Provider>) ACCESS.registries().get(null);
            var previous = registries.get();
            try {
                registries.set(player.registryAccess());
                return Optional.of(new StorageCraftingGrid(ACCESS.resolve().invoke(null, player, pos.asLong())));
            } finally {
                if (previous == null) registries.remove();
                else registries.set(previous);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to resolve AnvilCraft crafting grid", exception);
            return Optional.empty();
        }
    }

    public CraftingStorage read() throws ReflectiveOperationException {
        return (CraftingStorage) ACCESS.read().invoke(target);
    }

    public void write(CraftingStorage grid) throws ReflectiveOperationException {
        ACCESS.write().invoke(target, grid);
    }

    private static Access discover() {
        try {
            Field registries = StorageServerStub.class.getDeclaredField("REGISTRIES");
            Method resolve = StorageServerStub.class.getDeclaredMethod("resolveCraftingTarget", ServerPlayer.class, long.class);
            Method read = resolve.getReturnType().getDeclaredMethod("read");
            Method write = resolve.getReturnType().getDeclaredMethod("write", CraftingStorage.class);
            for (var member : new java.lang.reflect.AccessibleObject[]{registries, resolve, read, write}) {
                member.setAccessible(true);
            }
            return new Access(registries, resolve, read, write);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            AnvilAutoTree.LOGGER.warn("AnvilCraft crafting-grid interface unavailable", exception);
            return null;
        }
    }

    private record Access(Field registries, Method resolve, Method read, Method write) {
    }
}
