package dev.lanhuo.anvilautotree.client;

import com.mojang.datafixers.util.Either;
import dev.dubhe.anvilcraft.recipe.multiblock.IMultiblockRecipe;
import dev.lanhuo.anvilautotree.AnvilAutoTree;
import dev.lanhuo.anvilautotree.net.CraftRequestPayload;
import dev.lanhuo.anvilautotree.net.CraftResponsePayload;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端侧的自动合成交互状态机。
 *
 * <p>流程：JEI 的「+」被点击 → 拦截原转移并发规划请求 → 服务端回预览 → 弹出确认界面 →
 * 确认后请求执行 → 服务端完成后延迟重放原转移，把材料填进终端合成格。</p>
 *
 * <p>原转移必须重放而不是自己填格：终端合成格的真实填充语义（含桶装液体、请求份数）
 * 由 AnvilCraft 自己的 {@code craftingTransfer} 处理，重放可以完全复用。</p>
 */
public final class AutoCraftClient {
    private static final int REPLAY_INITIAL_DELAY = 20;
    private static final int REPLAY_RETRY_DELAY = 2;
    private static final int REPLAY_MAX_ATTEMPTS = 80;
    private static final int PLAN_TIMEOUT = 200;
    private static final int JOB_TIMEOUT = 600;
    private static final int QUANTITY_PREVIEW_DELAY = 4;

    private static Pending pending;
    private static Optional<UUID> plannedStorage = Optional.empty();
    private static boolean planFailed;
    private static boolean confirmed;
    private static int quantity = 1;
    private static int requestSequence;
    private static int requestId;
    private static int previewDelay;
    private static int replayDelay = -1;
    private static int replayAttempts;
    private static int waitTicks = -1;

    private AutoCraftClient() {
    }

    public static void plan(
        AbstractContainerMenu container,
        BlockPos sourcePos,
        RecipeHolder<?> recipe,
        Player player,
        Function<Player, IRecipeTransferError> transfer
    ) {
        plan(container, Either.left(sourcePos), recipe, player, transfer);
    }

    static void planPickBlock(UUID storageId, RecipeHolder<?> recipe, Player player) {
        plan(player.inventoryMenu, Either.right(storageId), recipe, player, null);
        Minecraft.getInstance().setScreen(new CraftTreePickScreen());
    }

    static void onPickScreenClosed() {
        if (pending != null && pending.pickBlock()) clear();
    }

    static boolean canStartPickBlock() {
        return pending == null;
    }

    private static void plan(
        AbstractContainerMenu container,
        Either<BlockPos, UUID> source,
        RecipeHolder<?> recipe,
        Player player,
        Function<Player, IRecipeTransferError> transfer
    ) {
        if (pending != null) {
            if (confirmed || (pending.container() == container && pending.recipe().id().equals(recipe.id()) && !planFailed)) {
                return;
            }
            clear();
        }
        pending = new Pending(container, transfer, recipe, source);
        requestId = ++requestSequence;
        sendPlan();
        player.displayClientMessage(Component.translatable("anvilautotree.status.planning"), true);
    }

    private static void sendPlan() {
        waitTicks = PLAN_TIMEOUT;
        sendRequest(Optional.empty());
    }

    private static void changeQuantity(int selected) {
        if (pending == null || confirmed || selected == quantity) {
            return;
        }
        quantity = selected;
        requestId = ++requestSequence;
        plannedStorage = Optional.empty();
        planFailed = false;
        waitTicks = -1;
        previewDelay = QUANTITY_PREVIEW_DELAY;
        CraftTreeOverlay.LIVE.markPlanning(quantity);
    }

    private static void sendRequest(Optional<UUID> storageId) {
        PacketDistributor.sendToServer(new CraftRequestPayload(
            pending.source(), pending.recipe().id(), pending.recipe().value() instanceof StonecutterRecipe,
            storageId, quantity, requestId
        ));
    }

    public static IRecipeTransferError missingIngredients() {
        return new AutoCraftTransferError(Component.translatable("anvilautotree.jei.tooltip"));
    }

    public static void onResponse(CraftResponsePayload payload) {
        if (pending == null || payload.requestId() != requestId) {
            return;
        }
        if (payload.phase() == CraftResponsePayload.PHASE_PLAN && !confirmed) {
            handlePlan(payload);
        } else if (payload.phase() == CraftResponsePayload.PHASE_ACCEPTED && confirmed) {
            waitTicks = -1;
        } else if (payload.phase() == CraftResponsePayload.PHASE_DONE && confirmed) {
            handleDone(payload);
        }
    }

    /**
     * 界面切换时放弃本次请求，避免侧边栏在别的界面里继续悬着。
     *
     * <p>保留请求的两种情况：新界面仍是这次请求所属的容器界面（点击「+」后 JEI 会关掉自己的配方界面
     * 并回到终端界面），以及新界面是 JEI 自己的界面（侧边栏会自动隐藏，回到终端时再显示）。</p>
     */
    public static void onScreenOpening(Screen newScreen) {
        if (pending == null) {
            return;
        }
        if (pending.pickBlock()) {
            if (!(newScreen instanceof CraftTreePickScreen)) clear();
            return;
        }
        if (newScreen instanceof AbstractContainerScreen<?> containerScreen && containerScreen.getMenu() == pending.container()) {
            return;
        }
        if (newScreen != null && newScreen.getClass().getName().startsWith("mezz.jei.")) {
            return;
        }
        clear();
    }

    /**
     * 客户端 tick：把延迟重放推进到下一步。
     */
    public static void tick() {
        if (pending == null) {
            return;
        }
        if (previewDelay > 0 && --previewDelay == 0) {
            sendPlan();
        }
        if (waitTicks > 0 && --waitTicks == 0) {
            CraftTreeOverlay.LIVE.hide();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                    Component.translatable("anvilautotree.status.timeout"),
                    false
                );
            }
            cancel();
            return;
        }
        if (replayDelay < 0) {
            return;
        }
        if (--replayDelay > 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || !pending.container().stillValid(player)) {
            clear();
            return;
        }
        IRecipeTransferError error;
        try {
            error = pending.transfer().apply(player);
        } catch (RuntimeException exception) {
            AnvilAutoTree.LOGGER.warn("Unable to replay the registered JEI recipe transfer", exception);
            player.displayClientMessage(Component.translatable("anvilautotree.status.replay_timeout"), false);
            clear();
            return;
        }
        if (error == null) {
            player.displayClientMessage(Component.translatable("anvilautotree.status.done"), false);
            clear();
            return;
        }
        if (++replayAttempts >= REPLAY_MAX_ATTEMPTS) {
            player.displayClientMessage(Component.translatable("anvilautotree.status.replay_timeout"), false);
            clear();
            return;
        }
        replayDelay = REPLAY_RETRY_DELAY;
    }

    private static void handlePlan(CraftResponsePayload payload) {
        if (pending == null) {
            return;
        }
        waitTicks = -1;
        plannedStorage = payload.storage().id();
        // 规划失败时也把树显示出来：缺失的物品会以红框节点出现，原因写在面板底部，
        // 不再只靠聊天栏文字提示。
        Component message = payload.success() ? null : payload.message();
        boolean canConfirm = payload.success() && plannedStorage.isPresent();
        if (CraftTreeOverlay.LIVE.isVisible()) {
            CraftTreeOverlay.LIVE.updatePlan(payload.tree(), message, canConfirm, payload.storage().kind(), quantity);
        } else {
            CraftTreeOverlay.LIVE.show(payload.tree(), message, canConfirm, payload.storage().kind(), quantity,
                AutoCraftClient::changeQuantity, AutoCraftClient::confirm, AutoCraftClient::cancel);
        }
        CraftTreeOverlay.LIVE.setDelivery(pending.recipe().value() instanceof IMultiblockRecipe, pending.pickBlock());
        if (!payload.success()) {
            // 失败时没有可执行的作业：标记为已结束，玩家补好材料后再点「+」会重新规划。
            planFailed = true;
        }
    }

    private static void handleDone(CraftResponsePayload payload) {
        if (pending == null) {
            return;
        }
        waitTicks = -1;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(payload.message(), !payload.success());
        }
        if (pending.pickBlock()) {
            cancel();
            return;
        }
        if (payload.success() && pending.transfer() == null && minecraft.screen instanceof
            dev.dubhe.anvilcraft.client.gui.screen.StorageScreen screen && screen.getMenu() == pending.container()) {
            screen.showCraftingAfterTransfer(false);
        }
        if (!payload.success() || quantity > 1 || pending.transfer() == null) {
            clear();
            return;
        }
        // 合成完成，面板收起来，把界面让给随后的原版转移重放。
        CraftTreeOverlay.LIVE.hide();
        replayDelay = REPLAY_INITIAL_DELAY;
        replayAttempts = 0;
    }

    private static void confirm() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pending == null || confirmed || minecraft.player == null || plannedStorage.isEmpty()) {
            return;
        }
        confirmed = true;
        sendRequest(plannedStorage);
        waitTicks = JOB_TIMEOUT;
        minecraft.player.displayClientMessage(Component.translatable("anvilautotree.status.crafting"), true);
    }

    private static void cancel() {
        clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CraftTreePickScreen) {
            minecraft.setScreen(null);
        }
    }

    private static void clear() {
        CraftTreeOverlay.LIVE.hide();
        pending = null;
        plannedStorage = Optional.empty();
        planFailed = false;
        confirmed = false;
        quantity = 1;
        previewDelay = 0;
        replayDelay = -1;
        replayAttempts = 0;
        waitTicks = -1;
    }

    private record Pending(
        AbstractContainerMenu container,
        Function<Player, IRecipeTransferError> transfer,
        RecipeHolder<?> recipe,
        Either<BlockPos, UUID> source
    ) {
        boolean pickBlock() {
            return source.right().isPresent();
        }
    }
}
