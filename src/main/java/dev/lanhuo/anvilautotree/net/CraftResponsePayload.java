package dev.lanhuo.anvilautotree.net;

import dev.lanhuo.anvilautotree.AnvilAutoTree;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端回执。
 *
 * <p>{@link #PHASE_PLAN} 是规划预览（客户端据此弹合成树确认界面），{@link #PHASE_DONE} 是执行结束
 * （成功时客户端重放 JEI 转移把材料填进终端合成格）。</p>
 *
 * @param tree 规划预览或缺料分支的扁平节点列表
 */
public record CraftResponsePayload(
    boolean success, int phase, Component message, List<CraftNodePayload> tree, Storage storage, int requestId
)
    implements CustomPacketPayload {
    public static final int PHASE_PLAN = 0;
    public static final int PHASE_DONE = 1;
    public static final int PHASE_ACCEPTED = 2;
    public static final Type<CraftResponsePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(AnvilAutoTree.MOD_ID, "craft_response")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftResponsePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        CraftResponsePayload::success,
        ByteBufCodecs.VAR_INT,
        CraftResponsePayload::phase,
        ComponentSerialization.STREAM_CODEC,
        CraftResponsePayload::message,
        CraftNodePayload.LIST_STREAM_CODEC,
        CraftResponsePayload::tree,
        Storage.STREAM_CODEC,
        CraftResponsePayload::storage,
        ByteBufCodecs.VAR_INT,
        CraftResponsePayload::requestId,
        CraftResponsePayload::new
    );

    public CraftResponsePayload {
        tree = List.copyOf(tree);
    }

    public record Storage(boolean shulker, Optional<UUID> id) {
        private static final StreamCodec<RegistryFriendlyByteBuf, Storage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Storage::shulker, ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Storage::id, Storage::new
        );
    }

    public static CraftResponsePayload done(boolean success, Component message, int requestId) {
        return new CraftResponsePayload(success, PHASE_DONE, message, List.of(), new Storage(false, Optional.empty()), requestId);
    }

    public static CraftResponsePayload accepted(int requestId) {
        return new CraftResponsePayload(true, PHASE_ACCEPTED, Component.empty(), List.of(),
            new Storage(false, Optional.empty()), requestId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
