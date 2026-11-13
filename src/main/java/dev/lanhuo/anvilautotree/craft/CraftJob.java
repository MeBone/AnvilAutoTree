package dev.lanhuo.anvilautotree.craft;

import dev.lanhuo.anvilautotree.net.CraftResponsePayload;
import dev.lanhuo.anvilautotree.net.StorageKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 一个正在执行的后台合成作业。
 *
 * <p>按 tick 预算推进：每个 tick 最多执行 {@code budget} 次合成，扣料与产物入存储都在
 * 同一 tick 内完成，避免出现「料已扣、产物未入」的中间态暴露给其他逻辑。</p>
 */
public final class CraftJob {
    private final ServerPlayer player;
    private final StorageAccess storage;
    private final List<CraftStep> steps;
    private final StorageKind kind;
    private final int requestId;
    private final int quantity;
    private final boolean pickBlock;
    private final Supplier<Component> packing;
    private boolean deliveredToHand;
    private int stepIndex;
    private int craftsInStep;
    private boolean failed;

    public CraftJob(ServerPlayer player, StorageAccess storage, List<CraftStep> steps, StorageKind kind, int requestId, int quantity, boolean pickBlock, Supplier<Component> packing) {
        this.player = player;
        this.storage = storage;
        this.steps = List.copyOf(steps);
        this.kind = kind;
        this.requestId = requestId;
        this.quantity = quantity;
        this.pickBlock = pickBlock;
        this.packing = packing;
    }

    /**
     * 推进至多 {@code budget} 次合成，返回实际消耗的次数。
     */
    public long tick(long budget) {
        long used = 0L;
        while (this.stepIndex < this.steps.size() && used < budget) {
            CraftStep step = this.steps.get(this.stepIndex);
            int remaining = step.crafts() - this.craftsInStep;
            int crafts = (int) Math.min(remaining, budget - used);
            if (crafts <= 0) {
                break;
            }
            if (!this.execute(step, crafts)) {
                this.failed = true;
                return used;
            }
            this.craftsInStep += crafts;
            used += crafts;
            if (this.craftsInStep >= step.crafts()) {
                this.stepIndex++;
                this.craftsInStep = 0;
            }
        }
        return used;
    }

    public boolean isFinished() {
        return this.failed || this.stepIndex >= this.steps.size();
    }

    /**
     * 异常中止：标记失败并直接跳到结束，让 {@link CraftJobManager} 正常回收并发失败回执。
     */
    public void abort() {
        this.failed = true;
        this.stepIndex = this.steps.size();
        this.craftsInStep = 0;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public int craftsPerTick(int hyperdimensionParallelism) {
        return this.kind.craftsPerTick(hyperdimensionParallelism);
    }

    public void complete() {
        Component message = this.failed
            ? Component.translatable("anvilautotree.status.failed")
            : Component.translatable(this.quantity > 1 ? "anvilautotree.status.batch_done" : "anvilautotree.status.done");
        if (!this.failed && packing != null) {
            Component error = packing.get();
            this.failed = error != null;
            message = this.failed ? error : Component.translatable(this.pickBlock
                ? "anvilautotree.status.structure_inventory_packed" : "anvilautotree.status.structure_packed");
        } else if (!this.failed && this.pickBlock) {
            message = Component.translatable(this.deliveredToHand
                ? "anvilautotree.status.pick_done" : "anvilautotree.status.pick_stored");
        }
        PacketDistributor.sendToPlayer(this.player, CraftResponsePayload.done(!this.failed, message, this.requestId));
    }

    private boolean execute(CraftStep step, int crafts) {
        List<ItemStack> consumed = new ArrayList<>(step.ingredients().size());
        for (IngredientChoice ingredient : step.ingredients()) {
            long needed = (long) ingredient.perCraft() * crafts;
            int amount = (int) Math.min(needed, Integer.MAX_VALUE);
            int extracted = this.storage.extract(ingredient.key().proto(), amount, false);
            if (extracted > 0) {
                consumed.add(ingredient.key().proto().copyWithCount(extracted));
            }
            if (extracted < amount) {
                // 中途缺料时优先把已经扣掉的原料退回仓储。
                for (ItemStack stack : consumed) {
                    while (!stack.isEmpty()) {
                        ItemStack leftover = this.storage.insert(stack.split(stack.getMaxStackSize()));
                        if (!leftover.isEmpty()) this.giveBack(leftover);
                    }
                }
                return false;
            }
        }
        this.insertOutput(step, crafts);
        return true;
    }

    /**
     * 产物入存储：按堆叠上限分块插入，避免一次性构造出超过堆叠上限的 {@link ItemStack}。
     */
    private void insertOutput(CraftStep step, int crafts) {
        int remaining = step.output().getCount() * crafts;
        int chunkSize = Math.max(1, step.output().getMaxStackSize());
        while (remaining > 0) {
            int count = Math.min(remaining, chunkSize);
            ItemStack output = step.output().copyWithCount(count);
            if (this.pickBlock && this.packing == null && this.stepIndex == this.steps.size() - 1) {
                this.deliverToHand(output);
            }
            ItemStack leftover = output.isEmpty() ? ItemStack.EMPTY : this.storage.insert(output);
            if (!leftover.isEmpty()) {
                this.giveBack(leftover);
            }
            remaining -= count;
        }
    }

    private void deliverToHand(ItemStack output) {
        Inventory inventory = this.player.getInventory();
        if (!this.deliveredToHand) {
            int slot = inventory.findSlotMatchingItem(output);
            if (slot < 0) {
                slot = inventory.getFreeSlot();
                if (slot < 0) return;
                inventory.setItem(slot, output.split(output.getMaxStackSize()));
            }
            if (Inventory.isHotbarSlot(slot)) {
                inventory.selected = slot;
            } else {
                inventory.pickSlot(slot);
                this.player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, slot, inventory.getItem(slot)));
            }
            this.player.connection.send(new ClientboundSetCarriedItemPacket(inventory.selected));
            this.deliveredToHand = true;
        }
        ItemStack held = inventory.getSelected();
        if (ItemStack.isSameItemSameComponents(held, output)) {
            held.grow(output.split(held.getMaxStackSize() - held.getCount()).getCount());
        }
        inventory.setChanged();
        this.player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, inventory.selected, held));
    }

    private void giveBack(ItemStack stack) {
        this.player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            this.player.drop(stack, false);
        }
    }
}
