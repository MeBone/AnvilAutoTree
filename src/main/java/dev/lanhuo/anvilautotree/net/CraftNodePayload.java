package dev.lanhuo.anvilautotree.net;

import dev.lanhuo.anvilautotree.craft.CraftTreeNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 合成树的网络节点：以「父节点下标 + 自身数据」的扁平列表传输，客户端再还原成树。
 *
 * @param parent   父节点下标，根节点为 -1
 * @param stack    节点物品（数量固定为 1，仅用于渲染）
 * @param required 父级配方这一次合成需要的数量
 * @param stored   规划开始时存储与背包里的现有数量
 * @param crafts   需要合成的次数，0 表示库存足够
 * @param missing  缺口无法补齐（没有可用配方或陷入自循环），客户端按红框显示
 */
public record CraftNodePayload(
    int parent,
    ItemStack stack,
    long required,
    long stored,
    int crafts,
    boolean missing
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftNodePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        CraftNodePayload::parent,
        ItemStack.OPTIONAL_STREAM_CODEC,
        CraftNodePayload::stack,
        ByteBufCodecs.VAR_LONG,
        CraftNodePayload::required,
        ByteBufCodecs.VAR_LONG,
        CraftNodePayload::stored,
        ByteBufCodecs.VAR_INT,
        CraftNodePayload::crafts,
        ByteBufCodecs.BOOL,
        CraftNodePayload::missing,
        CraftNodePayload::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, List<CraftNodePayload>> LIST_STREAM_CODEC =
        STREAM_CODEC.apply(ByteBufCodecs.list(512));

    /**
     * 把规划器产出的树拍平成节点列表。
     */
    public static List<CraftNodePayload> flatten(CraftTreeNode root) {
        List<CraftNodePayload> nodes = new ArrayList<>();
        if (root != null) {
            append(root, -1, nodes);
        }
        return nodes;
    }

    private static void append(CraftTreeNode node, int parent, List<CraftNodePayload> out) {
        int index = out.size();
        out.add(new CraftNodePayload(parent, node.stack(), node.required(), node.stored(), node.crafts(), node.missing()));
        for (CraftTreeNode child : node.children()) {
            append(child, index, out);
        }
    }
}
