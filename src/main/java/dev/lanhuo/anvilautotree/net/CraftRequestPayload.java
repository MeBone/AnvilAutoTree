package dev.lanhuo.anvilautotree.net;

import com.mojang.datafixers.util.Either;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import dev.lanhuo.anvilautotree.craft.RecipePreference;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端请求：对终端中当前展示的配方做一次自动补料。
 *
 * @param source 存储界面的位置，或中键取物所用的超维终端绑定
 * @param recipeId 目标配方
 * @param stonecutter 是否为切石机配方
 * @param quantity 目标成品数量，多方块配方为结构套数
 * @param requestId 客户端用于匹配预览或作业回执的编号
 * @param toGrid 确认超维批量订单后把成品放入合成栏
 * @param storageId 规划时为空，确认执行时为玩家预览的存储
 */
public record CraftRequestPayload(
    Either<BlockPos, UUID> source, Identifier recipeId, boolean stonecutter, Optional<UUID> storageId, int quantity, int requestId, boolean toGrid, Map<String, Identifier> preferences
)
    implements CustomPacketPayload {
    public static final Type<CraftRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(AnvilAutoTree.MOD_ID, "craft_request")
    );
    public CraftRequestPayload(Either<BlockPos, UUID> source, Identifier recipeId, boolean stonecutter,
                               Optional<UUID> storageId, int quantity, int requestId, boolean toGrid) {
        this(source, recipeId, stonecutter, storageId, quantity, requestId, toGrid, Map.of());
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, CraftRequestPayload> BASE_CODEC = StreamCodec.composite(
        ByteBufCodecs.either(BlockPos.STREAM_CODEC, UUIDUtil.STREAM_CODEC),
        CraftRequestPayload::source,
        Identifier.STREAM_CODEC,
        CraftRequestPayload::recipeId,
        ByteBufCodecs.VAR_INT,
        request -> (request.stonecutter() ? 1 : 0) | (request.toGrid() ? 2 : 0),
        ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC),
        CraftRequestPayload::storageId,
        ByteBufCodecs.VAR_INT,
        CraftRequestPayload::quantity,
        ByteBufCodecs.VAR_INT,
        CraftRequestPayload::requestId,
        (source, recipeId, flags, storageId, quantity, requestId) -> new CraftRequestPayload(
            source, recipeId, (flags & 1) != 0, storageId, quantity, requestId, (flags & 2) != 0)
    );

    private static final StreamCodec<RegistryFriendlyByteBuf, Map<String, Identifier>> PREFERENCES_CODEC =
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.stringUtf8(512), Identifier.STREAM_CODEC, RecipePreference.MAX_ENTRIES);
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftRequestPayload> STREAM_CODEC = StreamCodec.of(
        (buf, request) -> {
            BASE_CODEC.encode(buf, request);
            PREFERENCES_CODEC.encode(buf, request.preferences());
        },
        buf -> {
            var request = BASE_CODEC.decode(buf);
            return new CraftRequestPayload(request.source(), request.recipeId(), request.stonecutter(), request.storageId(),
                request.quantity(), request.requestId(), request.toGrid(), PREFERENCES_CODEC.decode(buf));
        }
    );

    public boolean pickBlock() {
        return source.right().isPresent();
    }

    public boolean execute() {
        return storageId.isPresent();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
