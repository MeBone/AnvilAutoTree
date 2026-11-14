package dev.lanhuo.anvilautotree.net;

import dev.lanhuo.anvilautotree.client.AutoCraftClient;
import dev.lanhuo.anvilautotree.craft.CraftService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络负载注册。
 */
public final class ModPayloads {
    private static final String PROTOCOL_VERSION = "9";

    private ModPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(CraftRequestPayload.TYPE, CraftRequestPayload.STREAM_CODEC, ModPayloads::handleRequest);
        registrar.playToClient(CraftResponsePayload.TYPE, CraftResponsePayload.STREAM_CODEC, ModPayloads::handleResponse);
    }

    private static void handleRequest(CraftRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> CraftService.handle(player, payload));
    }

    private static void handleResponse(CraftResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> AutoCraftClient.onResponse(payload));
    }
}
