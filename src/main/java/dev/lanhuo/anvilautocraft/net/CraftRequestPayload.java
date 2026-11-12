package dev.lanhuo.anvilautocraft.net;

import dev.lanhuo.anvilautocraft.AnvilAutoCraft;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端请求：对终端中当前展示的配方做一次自动补料。
 *
 * @param sourcePos 终端界面的存储位置（超维终端为虚拟坐标）
 * @param recipeId 目标配方
 * @param stonecutter 是否为切石机配方
 * @param quantity 目标配方执行次数
 * @param requestId 客户端用于匹配预览或作业回执的编号
 * @param storageId 规划时为空，确认执行时为玩家预览的存储
 */
public record CraftRequestPayload(
    BlockPos sourcePos, ResourceLocation recipeId, boolean stonecutter, Optional<UUID> storageId, int quantity, int requestId
)
    implements CustomPacketPayload {
    public static final int MAX_QUANTITY = 64;
    public static final Type<CraftRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(AnvilAutoCraft.MOD_ID, "craft_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftRequestPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC,
        CraftRequestPayload::sourcePos,
        ResourceLocation.STREAM_CODEC,
        CraftRequestPayload::recipeId,
        ByteBufCodecs.BOOL,
        CraftRequestPayload::stonecutter,
        ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC),
        CraftRequestPayload::storageId,
        ByteBufCodecs.VAR_INT,
        CraftRequestPayload::quantity,
        ByteBufCodecs.VAR_INT,
        CraftRequestPayload::requestId,
        CraftRequestPayload::new
    );

    public boolean execute() {
        return storageId.isPresent();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
