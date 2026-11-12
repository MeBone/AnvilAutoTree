package dev.lanhuo.anvilautocraft.storage;

import dev.dubhe.anvilcraft.block.entity.storage.ShulkerContainerBlockEntity;
import dev.dubhe.anvilcraft.rpc.StorageServerStub;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.saved.storage.HyperdimensionStorage;
import dev.dubhe.anvilcraft.saved.storage.ShulkerContainerStorage;
import dev.dubhe.anvilcraft.saved.storage.Storages;
import dev.lanhuo.anvilautocraft.AnvilAutoCraft;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;

public final class StorageLookup {
    private StorageLookup() {
    }

    public static Optional<BaseStorage<?>> resolve(ServerPlayer player, BlockPos sourcePos) {
        RemoteStorage remote = RemoteStorage.ACCESS;
        if (remote != null) {
            try {
                var playerId = player.getGameProfile().getId();
                long position = sourcePos.asLong();
                if (remote.sessions.get(null) instanceof Map<?, ?> sessions
                    && sessions.get(playerId) instanceof Map<?, ?> byPosition && byPosition.containsKey(position)) {
                    // Read the current view. A shulker terminal can change targets after opening.
                    Object view = remote.view.invoke(null, player.registryAccess(), playerId, position);
                    if (remote.primary.invoke(view) instanceof BaseStorage<?> storage
                        && (storage instanceof HyperdimensionStorage || storage instanceof ShulkerContainerStorage)) {
                        return Optional.of(storage);
                    }
                    return Optional.empty();
                }
            } catch (InvocationTargetException exception) {
                AnvilAutoCraft.LOGGER.warn("Unable to read the current AnvilCraft storage", exception.getCause());
                return Optional.empty();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                AnvilAutoCraft.LOGGER.warn("Unable to read AnvilCraft's remote storage session", exception);
                return Optional.empty();
            }
        }

        if (player.level().hasChunkAt(sourcePos) && player.canInteractWithBlock(sourcePos, 4.0)
            && player.level().getBlockEntity(sourcePos) instanceof ShulkerContainerBlockEntity container
            && container.getId() != null) {
            return Storages.get().get(container.getId()).filter(ShulkerContainerStorage.class::isInstance);
        }
        return Optional.empty();
    }

    private record RemoteStorage(Field sessions, Method view, Method primary) {
        private static final RemoteStorage ACCESS = discover();

        private static RemoteStorage discover() {
            try {
                Class<?> stub = StorageServerStub.class;
                Field sessions = namedOrUnique(Arrays.stream(stub.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()) && isRemoteSessionMap(field.getGenericType()))
                    .toArray(Field[]::new), "REMOTE_STORAGES").orElseThrow();
                Method view = namedOrUnique(Arrays.stream(stub.getDeclaredMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> Arrays.equals(method.getParameterTypes(),
                        new Class<?>[]{HolderLookup.Provider.class, UUID.class, long.class}))
                    .filter(method -> primaryAccessor(method.getReturnType()).isPresent())
                    .toArray(Method[]::new), "getView").orElseThrow();
                Method primary = primaryAccessor(view.getReturnType()).orElseThrow();
                sessions.setAccessible(true);
                view.setAccessible(true);
                primary.setAccessible(true);
                return new RemoteStorage(sessions, view, primary);
            } catch (RuntimeException | LinkageError exception) {
                AnvilAutoCraft.LOGGER.warn("AnvilCraft's remote storage interface is unavailable", exception);
                return null;
            }
        }

        private static Optional<Method> primaryAccessor(Class<?> viewType) {
            Method[] candidates = Arrays.stream(viewType.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic()
                    && method.getParameterCount() == 0 && BaseStorage.class.isAssignableFrom(method.getReturnType()))
                .toArray(Method[]::new);
            return namedOrUnique(candidates, "primary");
        }

        private static boolean isRemoteSessionMap(Type type) {
            return type instanceof ParameterizedType players && players.getRawType() == Map.class
                && players.getActualTypeArguments()[0] == UUID.class
                && players.getActualTypeArguments()[1] instanceof ParameterizedType positions
                && positions.getRawType() == Map.class && positions.getActualTypeArguments()[0] == Long.class
                && positions.getActualTypeArguments()[1] instanceof Class<?> target && target.isRecord()
                && Arrays.stream(target.getRecordComponents())
                    .anyMatch(component -> component.getName().equals("storageId") && component.getType() == UUID.class);
        }

        private static <T extends Member> Optional<T> namedOrUnique(T[] candidates, String name) {
            for (T candidate : candidates) {
                if (candidate.getName().equals(name)) {
                    return Optional.of(candidate);
                }
            }
            return candidates.length == 1 ? Optional.of(candidates[0]) : Optional.empty();
        }
    }
}
