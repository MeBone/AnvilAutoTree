package dev.lanhuo.anvilautotree.client;

import dev.lanhuo.anvilautotree.net.CraftNodePayload;
import dev.lanhuo.anvilautotree.net.StorageKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

/**
 * 悬浮在任意界面之上的合成树侧边栏。
 *
 * <p>不再替换当前界面，而是在 {@link net.neoforged.neoforge.client.event.ScreenEvent.Render.Post}
 * 中叠加绘制，并在点击/滚轮/拖动事件到达界面之前截获，因此 JEI 的配方界面与终端的容器界面都不会被顶掉。</p>
 *
 * <p>窗口行为对齐桌面窗口：拖标题栏移动、拖四边或四角改尺寸、Ctrl+滚轮缩放树、普通滚轮滚动、
 * 拖动视口平移内容；底部还有一个切换「只看缺失分支」的按钮。</p>
 */
public final class CraftTreeOverlay {
    private static final int NODE = 20;
    private static final int GAP_X = 10;
    private static final int GAP_Y = 20;
    private static final int PADDING = 6;
    private static final int HEADER = 30;
    private static final int FOOTER = 26;
    private static final int FOOTER_GAP = 4;
    private static final int QUANTITY_WIDTH = 36;
    private static final int BUTTON_HEIGHT = 18;
    private static final int CLOSE_WIDTH = 18;
    private static final int CLOSE_HEIGHT = 20;
    private static final int LEGEND_HEIGHT = 14;
    private static final int TREE_INSET = 4;
    private static final int RESIZE_BAND = 5;
    private static final float MIN_SCALE = 0.25F;
    private static final float MIN_AUTOFIT_SCALE = 0.85F;
    private static final float MAX_SCALE = 4.0F;
    private static final float ZOOM_STEP = 1.15F;
    /** 面板被自己的按钮关掉后，继续吞掉原区域输入的时间，用于躲开双击的第二下。 */
    private static final long IGNORE_INPUT_MS = 180L;

    private static final int SILVER = 0xFFC6C6C6;
    private static final int LIGHT_EDGE = 0xFFF5F5F5;
    private static final int DARK_EDGE = 0xFF555555;
    private static final int OUTLINE = 0xFF151515;
    private static final int INK = 0xFF282833;
    private static final int BORDER_CRAFT = 0xFF8E560D;
    private static final int BORDER_SATISFIED = 0xFF316641;
    private static final int BORDER_MISSING = 0xFF9D2B35;
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(
        "anvilcraft", "textures/gui/jei/slot_default.png"
    );
    private static final ResourceLocation CLOSE = ResourceLocation.fromNamespaceAndPath(
        "anvilcraft", "textures/gui/misc/storage_station/cancel.png"
    );
    private static final ResourceLocation HYPERDIMENSION_ICON = ResourceLocation.fromNamespaceAndPath(
        "anvilcraft", "hyperdimension_terminal"
    );
    private static final ResourceLocation SHULKER_ICON = ResourceLocation.fromNamespaceAndPath("anvilcraft", "shulker_container");
    private static final int SCROLL_BAR_THICKNESS = 5;
    /** 滚动条的点击/拖动判定带宽，比画出来的条更宽，方便抓。 */
    private static final int SCROLL_BAR_HIT = 7;

    private record Theme(String title, int frame, int header, int headerLight, int headerDark, int viewport, int hover) {
    }

    private static final Theme HYPERDIMENSION = new Theme(
        "anvilautotree.gui.title.hyperdimension", SILVER, 0xFFA8A9CD, 0xFFDADBF0, 0xFF737495, 0xFFAAAAAA, 0xFFB9BAE2
    );
    private static final Theme SHULKER = new Theme(
        "anvilautotree.gui.title.shulker", 0xFFC9B7CB, 0xFFD0A5CB, 0xFFF1D7EC, 0xFF8D6287, 0xFFB09CAF, 0xFFE0B7DB
    );
    private static final ResourceLocation CRATE_ICON = ResourceLocation.fromNamespaceAndPath("anvilcraft", "large_crate");
    private static final Theme CRATE = new Theme(
        "anvilautotree.gui.title.crate", 0xFFCFB595, 0xFFD8AD77, 0xFFF1D3AA, 0xFF8A603B, 0xFFAD9478, 0xFFE9C18C
    );
    private Theme theme = HYPERDIMENSION;
    private boolean structurePacking;
    private boolean pickBlock;
    private StorageKind storageKind = StorageKind.HYPERDIMENSION;

    private boolean visible;
    private List<CraftNodePayload> nodes = List.of();
    private Consumer<Boolean> onConfirm = ignored -> {
    };
    private Runnable onCancel = () -> {
    };
    private Component status;
    private int quantity = 1;
    private EditBox quantityInput;
    private boolean validQuantityInput = true;
    private IntConsumer onQuantityChanged = ignored -> {};
    private boolean confirmEnabled = true;
    private boolean planning;
    /** 已经点过「开始合成」、正在等服务端回执，避免连点发出多次合成请求。 */
    private boolean busy;

    private AnvilLibWindow windowChrome;
    private Footer footer;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int requestedWidth;
    private int requestedHeight;
    private int requestedX;
    private int requestedY;

    private float scale = 1.0F;
    private float zoom = 1.0F;
    private double scrollX;
    private double scrollY;
    private boolean onlyMissing;
    private boolean showingMissingAutomatically;

    private Map<Integer, List<Integer>> children = Map.of();
    private Map<Integer, int[]> positions = Map.of();
    private Map<Integer, Integer> widths = Map.of();
    private int root = -1;
    private int contentWidth;
    private int contentHeight;
    private int lastOriginX;
    private int lastOriginY;

    private boolean draggingPanel;
    private boolean draggingContent;
    private boolean draggingScrollX;
    private boolean draggingScrollY;
    /** 拖动滚动条时的鼠标起点与对应滚动量。 */
    private double scrollDragAnchor;
    private double scrollDragStart;
    private boolean pressConsumed;
    private boolean resizing;
    private int resizeMask;
    /**
     * 面板内的按钮关掉面板后，短暂吞掉输入（毫秒时间戳）。
     *
     * <p>否则双击「开始合成」的第二下会穿透到下层终端界面，意外抓起物品。</p>
     */
    private long ignoreInputBefore;
    private double dragAnchorX;
    private double dragAnchorY;
    private int dragPanelX;
    private int dragPanelY;
    private double dragScrollX;
    private double dragScrollY;
    private int resizeStartX;
    private int resizeStartY;
    private int resizeStartWidth;
    private int resizeStartHeight;

    public static final CraftTreeOverlay LIVE = new CraftTreeOverlay(false);
    private final boolean layoutPreview;

    CraftTreeOverlay(boolean layoutPreview) {
        this.layoutPreview = layoutPreview;
    }

    /**
     * 显示面板。
     *
     * @param payloads       扁平化的合成树节点
     * @param status         面板底部要显示的提示（规划失败的原因等），可为 null
     * @param canConfirm     是否允许点「开始合成」
     */
    public void show(
        List<CraftNodePayload> payloads, Component status, boolean canConfirm, StorageKind kind, int quantity,
        IntConsumer changeQuantity, Consumer<Boolean> confirm, Runnable cancel
    ) {
        structurePacking = false;
        pickBlock = false;
        storageKind = kind;
        onQuantityChanged = changeQuantity;
        onConfirm = confirm;
        onCancel = cancel;
        busy = false;
        visible = true;
        zoom = 1.0F;
        scrollX = 0.0D;
        scrollY = 0.0D;
        draggingPanel = false;
        draggingContent = false;
        draggingScrollX = false;
        draggingScrollY = false;
        resizing = false;
        resizeMask = 0;
        pressConsumed = false;
        ignoreInputBefore = 0L;
        // 规划失败时默认直接聚焦到补不齐的那几条分支，玩家一开面板就能看到缺什么。
        onlyMissing = false;
        showingMissingAutomatically = false;
        initQuantityInput(quantity);
        updatePlan(payloads, status, canConfirm, kind, quantity);
        setBounds(AutoCraftClientConfig.currentLayout());
        centerRoot();
    }

    private void initQuantityInput(int initialValue) {
        footer = null;
        quantityInput = new EditBox(Minecraft.getInstance().font, 0, 0, QUANTITY_WIDTH, BUTTON_HEIGHT,
            Component.translatable("anvilautotree.gui.quantity_label"));
        quantityInput.setMaxLength(4);
        quantityInput.setFilter(value -> value.chars().allMatch(character -> character >= '0' && character <= '9'));
        quantityInput.setValue(Integer.toString(initialValue));
        quantityInput.setEditable(!layoutPreview);
        validQuantityInput = true;
        quantityInput.setResponder(value -> {
            int number = value.isEmpty() ? 0 : Integer.parseInt(value);
            validQuantityInput = number >= 1 && number <= storageKind.maxOrderQuantity();
            quantityInput.setTextColor(validQuantityInput ? 0xFFECECF4 : 0xFFFF8989);
            if (validQuantityInput && number != quantity) {
                onQuantityChanged.accept(number);
            }
        });
    }

    public void updatePlan(List<CraftNodePayload> payloads, Component message, boolean canConfirm, StorageKind kind, int quantity) {
        boolean hadTree = root >= 0;
        boolean wasFiltered = onlyMissing;
        nodes = List.copyOf(payloads);
        status = message;
        planning = false;
        confirmEnabled = canConfirm;
        storageKind = kind;
        theme = switch (kind) {
            case HYPERDIMENSION -> HYPERDIMENSION;
            case SHULKER -> SHULKER;
            case LARGE_CRATE -> CRATE;
        };
        quantityInput.setEditable(!layoutPreview && kind.supportsBatch());
        this.quantity = quantity;
        if (!canConfirm && !onlyMissing && payloads.stream().anyMatch(CraftNodePayload::missing)) {
            onlyMissing = true;
            showingMissingAutomatically = true;
        } else if (canConfirm && showingMissingAutomatically) {
            onlyMissing = false;
            showingMissingAutomatically = false;
        }
        rebuild();
        if (hadTree && wasFiltered != onlyMissing) {
            scrollY = 0.0D;
            ensurePlaced();
            centerRoot();
        }
    }

    void setDelivery(boolean structurePacking, boolean pickBlock) {
        this.structurePacking = structurePacking;
        this.pickBlock = pickBlock;
    }

    public void markPlanning(int quantity) {
        this.quantity = quantity;
        confirmEnabled = false;
        planning = true;
        status = Component.translatable("anvilautotree.status.planning");
    }

    public boolean confirmFromKey() {
        if (!active() || layoutPreview) {
            return false;
        }
        tryConfirm(false);
        return true;
    }

    public boolean quantityKeyPressed(int key, int scanCode, int modifiers) {
        if (!editingQuantity()) {
            return false;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> finishQuantityEdit();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (validQuantityInput) {
                    finishQuantityEdit();
                }
            }
            case GLFW.GLFW_KEY_SPACE -> {
                if (validQuantityInput) {
                    finishQuantityEdit();
                    tryConfirm(false);
                }
            }
            default -> quantityInput.keyPressed(key, scanCode, modifiers);
        }
        return true;
    }

    public boolean quantityCharTyped(char character, int modifiers) {
        if (!editingQuantity()) {
            return false;
        }
        quantityInput.charTyped(character, modifiers);
        return true;
    }

    private boolean editingQuantity() {
        return active() && !layoutPreview && quantityInput.isFocused();
    }

    private void finishQuantityEdit() {
        quantityInput.setFocused(false);
        if (!validQuantityInput) {
            quantityInput.setValue(Integer.toString(quantity));
        }
    }

    Rect2i requestedBounds() {
        return new Rect2i(requestedX, requestedY, requestedWidth, requestedHeight);
    }

    void setBounds(Rect2i bounds) {
        requestedX = bounds.getX();
        requestedY = bounds.getY();
        requestedWidth = bounds.getWidth();
        requestedHeight = bounds.getHeight();
        ensurePlaced();
    }

    public void hide() {
        visible = false;
        quantityInput = null;
        nodes = List.of();
        status = null;
        confirmEnabled = true;
        busy = false;
        draggingPanel = false;
        draggingContent = false;
        draggingScrollX = false;
        draggingScrollY = false;
        resizing = false;
        resizeMask = 0;
        children = Map.of();
        positions = Map.of();
        widths = Map.of();
        root = -1;
        contentWidth = 0;
        contentHeight = 0;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * 供 JEI 的容器界面处理器上报占位区域，让 JEI 自己把配方/材料列表挪开。
     */
    public List<Rect2i> extraAreas() {
        if (!active()) {
            return List.of();
        }
        ensurePlaced();
        return List.of(new Rect2i(panelX, panelY, panelWidth, panelHeight));
    }

    /**
     * 在界面绘制完成之后叠加绘制面板。
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        ensurePlaced();

        int viewX = panelX + PADDING;
        int viewY = panelY + HEADER;
        int viewWidth = panelWidth - PADDING * 2;
        List<FormattedCharSequence> statusLines = statusLines(font);
        int viewHeight = viewHeight(statusLines.size());

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        if (windowChrome != null) {
            windowChrome.render(graphics);
        } else {
            graphics.fill(panelX + 2, panelY + 2, panelX + panelWidth + 2, panelY + panelHeight + 2, 0x66000000);
            bevel(graphics, panelX, panelY, panelWidth, panelHeight, theme.frame(), LIGHT_EDGE, DARK_EDGE);
        }
        int closeX = closeX();
        bevel(graphics, viewX, panelY + PADDING, closeX - viewX - 3, CLOSE_HEIGHT,
            theme.header(), theme.headerLight(), theme.headerDark());
        ItemStack storageIcon = (switch (storageKind) {
            case HYPERDIMENSION -> BuiltInRegistries.ITEM.getOptional(HYPERDIMENSION_ICON).orElse(Items.ENDER_EYE);
            case SHULKER -> BuiltInRegistries.ITEM.getOptional(SHULKER_ICON).orElse(Items.SHULKER_SHELL);
            case LARGE_CRATE -> BuiltInRegistries.ITEM.getOptional(CRATE_ICON).orElse(Items.BARREL);
        }).getDefaultInstance();
        graphics.renderItem(storageIcon, viewX + 4, panelY + 8);
        int titleX = viewX + 23;
        String title = fitLabel(font, Component.translatable(theme.title()), closeX - titleX - 7);
        graphics.drawString(font, title, titleX, panelY + 12, INK, false);
        boolean closeHovered = inRect(mouseX, mouseY, closeX, panelY + PADDING, CLOSE_WIDTH, CLOSE_HEIGHT);
        if (minecraft.getResourceManager().getResource(CLOSE).isPresent()) {
            graphics.blit(CLOSE, closeX, panelY + PADDING, 0, closeHovered ? CLOSE_HEIGHT : 0, CLOSE_WIDTH, CLOSE_HEIGHT, 18, 40);
        } else {
            bevel(graphics, closeX, panelY + PADDING, CLOSE_WIDTH, CLOSE_HEIGHT,
                closeHovered ? theme.hover() : SILVER, LIGHT_EDGE, DARK_EDGE);
            drawCenteredLabel(graphics, font, Component.literal("×"), closeX, panelY + 12, CLOSE_WIDTH, INK);
        }
        bevel(graphics, viewX, viewY - 2, viewWidth, viewHeight + 4, theme.viewport(), DARK_EDGE, LIGHT_EDGE);

        if (root < 0) {
            // 有失败原因时底部已经在显示原因，视口里不再重复一句「没有步骤」。
            if (statusLines.isEmpty()) {
                Component empty = Component.translatable(
                    onlyMissing ? "anvilautotree.gui.no_missing" : "anvilautotree.gui.empty"
                );
                drawCenteredLabel(graphics, font, empty, viewX, viewY + viewHeight / 2 - 4, viewWidth, INK);
            }
        } else {
            renderTree(graphics, viewX, viewY, viewWidth, viewHeight);
        }

        int legendY = viewY + viewHeight + 5;
        renderLegend(graphics, font, viewX, legendY, viewWidth);
        for (int index = 0; index < statusLines.size(); index++) {
            graphics.drawString(font, statusLines.get(index), viewX, legendY + LEGEND_HEIGHT + index * 10,
                planning ? INK : BORDER_MISSING, false);
        }
        if (status != null && !planning && inRect(mouseX, mouseY,
            viewX, legendY + LEGEND_HEIGHT, viewWidth, statusLines.size() * 10)) {
            graphics.renderTooltip(font, status, mouseX, mouseY);
        }

        renderFooter(graphics, font, mouseX, mouseY);
        renderResizeGrip(graphics);
        if (root >= 0) {
            renderTooltip(graphics, font, mouseX, mouseY, viewX, viewY, viewWidth, viewHeight);
        }
        graphics.pose().popPose();
    }

    /**
     * 右下角画三道斜线，提示这里可以拖动改变窗口尺寸。
     */
    private void renderResizeGrip(GuiGraphics graphics) {
        int right = panelX + panelWidth - 2;
        int bottom = panelY + panelHeight - 3;
        for (int length = 2; length <= 6; length += 2) {
            int y = bottom - (6 - length);
            graphics.fill(right - length, y, right, y + 1, DARK_EDGE);
        }
    }

    private static void bevel(GuiGraphics graphics, int x, int y, int width, int height, int fill, int top, int bottom) {
        graphics.fill(x, y, x + width, y + height, OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, top);
        graphics.fill(x + 2, y + 2, x + width - 1, y + height - 1, bottom);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, fill);
    }

    private void renderLegend(GuiGraphics graphics, Font font, int x, int y, int width) {
        Component stored = Component.translatable("anvilautotree.gui.legend_stored");
        Component craft = Component.translatable("anvilautotree.gui.legend_craft");
        Component missing = Component.translatable("anvilautotree.gui.legend_missing");
        int totalWidth = font.width(stored) + font.width(craft) + font.width(missing) + 40;
        if (totalWidth <= width) {
            int left = x + (width - totalWidth) / 2;
            left = drawLegendEntry(graphics, font, stored, left, y, BORDER_SATISFIED);
            left = drawLegendEntry(graphics, font, craft, left, y, BORDER_CRAFT);
            drawLegendEntry(graphics, font, missing, left, y, BORDER_MISSING);
        }
    }

    private static int drawLegendEntry(GuiGraphics graphics, Font font, Component label, int x, int y, int color) {
        graphics.fill(x, y + 1, x + 6, y + 7, color);
        graphics.drawString(font, label, x + 9, y, INK, false);
        return x + 16 + font.width(label);
    }

    private int closeX() {
        return panelX + panelWidth - PADDING - CLOSE_WIDTH;
    }

    /**
     * 提示文字按面板宽度折行，最多三行，避免把视口挤没。
     */
    private List<FormattedCharSequence> statusLines(Font font) {
        if (status == null) {
            return List.of();
        }
        List<FormattedCharSequence> lines = font.split(status, Math.max(40, panelWidth - PADDING * 2));
        int maxLines = clampInt((viewHeight(0) - 28) / 10, 1, 3);
        return lines.subList(0, Math.min(lines.size(), maxLines));
    }

    private static boolean isJeiScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.startsWith("mezz.jei.");
    }

    /**
     * 面板是否应当参与绘制与输入：只有存在当前界面、且当前界面不是 JEI 自己的界面时才生效。
     *
     * <p>JEI 的配方界面是全屏内容，盖在上面会挡住配方；JEI 在容器界面上的材料列表则通过
     * {@link #extraAreas()} 自行让位。</p>
     */
    private boolean active() {
        if (!visible) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.screen != null && !isJeiScreen(minecraft.screen);
    }

    private void renderTree(GuiGraphics graphics, int viewX, int viewY, int viewWidth, int viewHeight) {
        double maxScrollX = maxScrollX(viewWidth);
        double maxScrollY = maxScrollY(viewHeight);
        scrollX = clamp(scrollX, 0.0D, maxScrollX);
        scrollY = clamp(scrollY, 0.0D, maxScrollY);

        int originX = viewX + axisOffset(viewWidth, contentWidth, scrollX);
        int originY = viewY + axisOffset(viewHeight, contentHeight, scrollY);
        lastOriginX = originX;
        lastOriginY = originY;

        graphics.enableScissor(viewX, viewY, viewX + viewWidth, viewY + viewHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(originX, originY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        renderLines(graphics);
        boolean hasSlotTexture = Minecraft.getInstance().getResourceManager().getResource(SLOT).isPresent();
        for (int index = 0; index < nodes.size(); index++) {
            renderNode(graphics, index, hasSlotTexture);
        }
        graphics.pose().popPose();
        graphics.disableScissor();

        renderScrollBars(graphics, viewX, viewY, viewWidth, viewHeight, maxScrollX, maxScrollY);
        graphics.enableScissor(viewX, viewY, viewX + viewWidth, viewY + viewHeight);
        renderCounts(graphics, originX, originY);
        graphics.disableScissor();
    }

    private void renderLines(GuiGraphics graphics) {
        int thickness = (int) Math.ceil(1.0F / scale);
        for (Map.Entry<Integer, List<Integer>> entry : children.entrySet()) {
            int[] parent = positions.get(entry.getKey());
            if (parent == null) {
                continue;
            }
            for (int child : entry.getValue()) {
                int[] childPos = positions.get(child);
                if (childPos == null) {
                    continue;
                }
                int startX = parent[0] + NODE / 2;
                int startY = parent[1] + NODE;
                int endX = childPos[0] + NODE / 2;
                int endY = childPos[1];
                int midY = (startY + endY) / 2;
                graphics.fill(startX, startY, startX + thickness, midY, DARK_EDGE);
                if (endX != startX) {
                    graphics.fill(Math.min(startX, endX), midY, Math.max(startX, endX) + thickness, midY + thickness, DARK_EDGE);
                }
                graphics.fill(endX, midY, endX + thickness, endY, DARK_EDGE);
            }
        }
    }

    private void renderNode(GuiGraphics graphics, int index, boolean hasSlotTexture) {
        int[] position = positions.get(index);
        if (position == null) {
            return;
        }
        CraftNodePayload node = nodes.get(index);
        graphics.renderOutline(position[0], position[1], NODE, NODE, borderColor(node));
        if (hasSlotTexture) {
            graphics.blit(SLOT, position[0] + 1, position[1] + 1, 0, 0, 18, 18, 18, 18);
        } else {
            bevel(graphics, position[0] + 1, position[1] + 1, 18, 18, 0xFF8B8B8B, DARK_EDGE, LIGHT_EDGE);
        }
        graphics.renderItem(node.stack(), position[0] + 2, position[1] + 2);
    }

    private void renderCounts(GuiGraphics graphics, int originX, int originY) {
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        // Item models write depth. Draw the count in front of them, like a slot decoration.
        graphics.pose().translate(originX, originY, 250);
        graphics.pose().scale(scale, scale, 1.0F);
        for (int index = 0; index < nodes.size(); index++) {
            int[] position = positions.get(index);
            if (position == null) {
                continue;
            }
            String label = Long.toString(nodes.get(index).required());
            int width = font.width(label);
            float textScale = Math.min(1.0F, (NODE - 2) / (float) width);
            graphics.pose().pushPose();
            graphics.pose().translate(position[0] + NODE - 1, position[1] + NODE - 1, 0);
            graphics.pose().scale(textScale, textScale, 1.0F);
            graphics.drawString(font, label, -width, -8, 0xFFFFFFFF, true);
            graphics.pose().popPose();
        }
        graphics.pose().popPose();
    }

    private static int borderColor(CraftNodePayload node) {
        if (isMissing(node)) {
            return BORDER_MISSING;
        }
        return node.crafts() > 0 ? BORDER_CRAFT : BORDER_SATISFIED;
    }

    /**
     * 红框节点：缺口无法由合成补齐（没有配方或自循环）。规划失败时这些节点就是缺失的物品。
     */
    private static boolean isMissing(CraftNodePayload node) {
        return node.missing();
    }

    private void renderTooltip(
        GuiGraphics graphics,
        Font font,
        int mouseX,
        int mouseY,
        int viewX,
        int viewY,
        int viewWidth,
        int viewHeight
    ) {
        if (mouseX < viewX || mouseX >= viewX + viewWidth || mouseY < viewY || mouseY >= viewY + viewHeight) {
            return;
        }
        int hovered = nodeAt(mouseX, mouseY);
        if (hovered < 0) {
            return;
        }
        CraftNodePayload node = nodes.get(hovered);
        List<Component> lines = new ArrayList<>();
        lines.add(node.stack().getHoverName());
        lines.add(Component.translatable("anvilautotree.gui.node_required", node.required()));
        lines.add(Component.translatable("anvilautotree.gui.node_stored", node.stored()));
        if (isMissing(node) && hovered != root) {
            lines.add(Component.translatable("anvilautotree.gui.node_missing", Math.max(0L, node.required() - node.stored())));
        } else if (!isMissing(node) && node.crafts() > 0) {
            lines.add(Component.translatable("anvilautotree.gui.node_crafts", node.crafts()));
        }
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    private int nodeAt(int mouseX, int mouseY) {
        for (int index = 0; index < nodes.size(); index++) {
            int[] position = positions.get(index);
            if (position == null) {
                continue;
            }
            int left = lastOriginX + Math.round(position[0] * scale);
            int top = lastOriginY + Math.round(position[1] * scale);
            int size = Math.round(NODE * scale);
            if (mouseX >= left && mouseX < left + size && mouseY >= top && mouseY < top + size) {
                return index;
            }
        }
        return -1;
    }

    private void renderScrollBars(
        GuiGraphics graphics,
        int viewX,
        int viewY,
        int viewWidth,
        int viewHeight,
        double maxScrollX,
        double maxScrollY
    ) {
        if (maxScrollY > 0.5D) {
            int barX = viewX + viewWidth - SCROLL_BAR_THICKNESS;
            int thumbHeight = verticalThumbLength(viewHeight);
            int travel = Math.max(1, viewHeight - thumbHeight);
            int thumbY = viewY + (int) Math.round((double) travel * scrollY / maxScrollY);
            graphics.fill(barX, viewY, barX + SCROLL_BAR_THICKNESS, viewY + viewHeight, 0x40000000);
            bevel(graphics, barX, thumbY, SCROLL_BAR_THICKNESS, thumbHeight, SILVER, LIGHT_EDGE, DARK_EDGE);
        }
        if (maxScrollX > 0.5D) {
            int barY = viewY + viewHeight - SCROLL_BAR_THICKNESS;
            int thumbWidth = horizontalThumbLength(viewWidth);
            int travel = Math.max(1, viewWidth - thumbWidth);
            int thumbX = viewX + (int) Math.round((double) travel * scrollX / maxScrollX);
            graphics.fill(viewX, barY, viewX + viewWidth, barY + SCROLL_BAR_THICKNESS, 0x40000000);
            bevel(graphics, thumbX, barY, thumbWidth, SCROLL_BAR_THICKNESS, SILVER, LIGHT_EDGE, DARK_EDGE);
        }
    }

    private int verticalThumbLength(int viewHeight) {
        long total = Math.max(1L, Math.round(contentHeight * (double) scale));
        return Math.max(12, (int) Math.min(viewHeight, viewHeight * (long) viewHeight / total));
    }

    private int horizontalThumbLength(int viewWidth) {
        long total = Math.max(1L, Math.round(contentWidth * (double) scale));
        return Math.max(12, (int) Math.min(viewWidth, viewWidth * (long) viewWidth / total));
    }

    private void renderFooter(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        renderButton(graphics, font, structurePacking ? "anvilautotree.gui.structure_pack" : "anvilautotree.gui.confirm",
            footer.confirm(), mouseX, mouseY, !layoutPreview && confirmEnabled && validQuantityInput && !busy, false,
            0);
        quantityInput.render(graphics, mouseX, mouseY, 0);
        renderButton(graphics, font, onlyMissing ? "anvilautotree.gui.filter_all" : "anvilautotree.gui.filter_only",
            footer.filter(), mouseX, mouseY, !layoutPreview, onlyMissing, 1);
        renderButton(graphics, font, "anvilautotree.gui.cancel", footer.cancel(), mouseX, mouseY, !layoutPreview, false,
            2);
        if (!layoutPreview && inRect(mouseX, mouseY, footer.quantity())) {
            if (!storageKind.supportsBatch()) {
                graphics.renderTooltip(font, Component.translatable("anvilautotree.error.crate_single_only"), mouseX, mouseY);
            } else {
                String hint = structurePacking ? "anvilautotree.gui.structure_quantity_hint" : "anvilautotree.gui.quantity_hint";
                List<Component> lines = new ArrayList<>();
                lines.add(Component.translatable(hint, storageKind.maxOrderQuantity()));
                if (!structurePacking && (pickBlock || quantity > 1) && root >= 0 && !planning && confirmEnabled) {
                    long output = nodes.get(root).required();
                    if (output > quantity) {
                        lines.add(Component.translatable("anvilautotree.gui.quantity_rounded", output));
                    }
                }
                graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
            }
        } else if (!layoutPreview && inRect(mouseX, mouseY, footer.confirm())) {
            if (canSendToGrid()) {
                graphics.renderTooltip(font, List.of(
                    Component.translatable("anvilautotree.gui.batch_hint"),
                    Component.translatable("anvilautotree.gui.batch_grid_hint")), Optional.empty(), mouseX, mouseY);
                return;
            }
            graphics.renderTooltip(font, Component.translatable(
                structurePacking
                    ? (pickBlock ? "anvilautotree.gui.structure_inventory_hint" : "anvilautotree.gui.structure_pack_hint")
                    : (pickBlock ? "anvilautotree.gui.pick_hint"
                        : quantity > 1 ? "anvilautotree.gui.batch_hint" : "anvilautotree.gui.confirm_hint")), mouseX, mouseY);
        }
    }

    private void layoutQuantityInput(Rect2i bounds) {
        quantityInput.setPosition(bounds.getX(), bounds.getY());
        quantityInput.setWidth(bounds.getWidth());
    }

    private void renderButton(
        GuiGraphics graphics, Font font, String key, Rect2i bounds,
        int mouseX, int mouseY, boolean enabled, boolean selected, int index
    ) {
        int x = bounds.getX();
        int y = bounds.getY();
        int width = bounds.getWidth();
        boolean hovered = enabled && inRect(mouseX, mouseY, bounds);
        if (windowChrome != null) {
            windowChrome.renderButton(index, graphics, enabled, hovered, selected);
        } else {
            int fill = SILVER;
            if (!enabled) {
                fill = 0xFFB7B7B7;
            } else if (hovered) {
                fill = theme.hover();
            } else if (selected) {
                fill = theme.header();
            }
            bevel(graphics, x, y, width, BUTTON_HEIGHT, fill,
                selected ? DARK_EDGE : LIGHT_EDGE, selected ? LIGHT_EDGE : DARK_EDGE);
        }
        drawCenteredLabel(graphics, font, Component.translatable(key), x, y + 5, width, enabled ? INK : DARK_EDGE);
    }

    private static void drawCenteredLabel(GuiGraphics graphics, Font font, Component label, int x, int y, int width, int color) {
        String text = fitLabel(font, label, width - 6);
        graphics.drawString(font, text, x + (width - font.width(text)) / 2, y, color, false);
    }

    private static String fitLabel(Font font, Component label, int width) {
        String text = label.getString();
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    private Footer createFooter() {
        int x = panelX + PADDING;
        int y = panelY + panelHeight - FOOTER + 3;
        int total = Math.max(4, panelWidth - PADDING * 2 - FOOTER_GAP * 3);
        int quantityWidth = Math.min(QUANTITY_WIDTH, total / 4);
        int buttonSpace = total - quantityWidth;
        int cancelWidth = Math.max(1, buttonSpace / 4);
        int confirmWidth = Math.max(1, (buttonSpace - cancelWidth) / 2);
        int filterWidth = Math.max(1, buttonSpace - cancelWidth - confirmWidth);
        Rect2i confirm = new Rect2i(x, y, confirmWidth, BUTTON_HEIGHT);
        Rect2i quantity = new Rect2i(x + confirmWidth + FOOTER_GAP, y, quantityWidth, BUTTON_HEIGHT);
        Rect2i filter = new Rect2i(quantity.getX() + quantityWidth + FOOTER_GAP, y, filterWidth, BUTTON_HEIGHT);
        Rect2i cancel = new Rect2i(filter.getX() + filterWidth + FOOTER_GAP, y, cancelWidth, BUTTON_HEIGHT);
        return new Footer(confirm, quantity, filter, cancel);
    }

    private record Footer(Rect2i confirm, Rect2i quantity, Rect2i filter, Rect2i cancel) {
    }

    /**
     * 处理界面收到的鼠标按下（已由事件拦截层调用），返回是否消费该事件。
     */
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (!active()) {
            return inIgnoreWindow(mouseX, mouseY);
        }
        ensurePlaced();
        boolean validBeforeClick = validQuantityInput;
        if (!layoutPreview && inRect(mouseX, mouseY, footer.quantity())) {
            pressConsumed = true;
            if (!storageKind.supportsBatch()) {
                return true;
            }
            boolean alreadyFocused = quantityInput.isFocused();
            quantityInput.setFocused(true);
            quantityInput.mouseClicked(mouseX, mouseY, button);
            if (!alreadyFocused) {
                quantityInput.setCursorPosition(quantityInput.getValue().length());
                quantityInput.setHighlightPos(0);
            }
            return true;
        }
        if (editingQuantity()) {
            finishQuantityEdit();
        }
        // Extend the resize hit area two pixels beyond the frame.
        boolean nearBorder = inRect(mouseX, mouseY, panelX - 2, panelY - 2, panelWidth + 4, panelHeight + 4);
        int mask = nearBorder ? resizeMaskAt(mouseX, mouseY) : 0;
        if (mask == 0 && !inRect(mouseX, mouseY, panelX, panelY, panelWidth, panelHeight)) {
            return false;
        }
        pressConsumed = true;

        if (mask != 0) {
            resizing = true;
            resizeMask = mask;
            resizeStartX = panelX;
            resizeStartY = panelY;
            resizeStartWidth = panelWidth;
            resizeStartHeight = panelHeight;
            dragAnchorX = mouseX;
            dragAnchorY = mouseY;
            return true;
        }

        if (!layoutPreview && inRect(mouseX, mouseY, closeX(), panelY + PADDING, CLOSE_WIDTH, CLOSE_HEIGHT)) {
            cancel();
            return true;
        }

        if (mouseY < panelY + HEADER) {
            draggingPanel = true;
            dragAnchorX = mouseX;
            dragAnchorY = mouseY;
            dragPanelX = panelX;
            dragPanelY = panelY;
            return true;
        }
        if (layoutPreview) {
            return true;
        }
        if (mouseY >= panelY + panelHeight - FOOTER) {
            if (inRect(mouseX, mouseY, footer.confirm()) && validBeforeClick) {
                tryConfirm(Screen.hasShiftDown() && canSendToGrid());
            } else if (inRect(mouseX, mouseY, footer.filter())) {
                toggleOnlyMissing();
            } else if (inRect(mouseX, mouseY, footer.cancel())) {
                cancel();
            }
            return true;
        }
        // 只有标题栏、边距这些「窗口边缘」区域才拖动整个窗口；
        // 树的内容区留给拖动平移，否则树没法在窗口里挪动。
        int viewX = panelX + PADDING;
        int viewY = panelY + HEADER;
        int viewWidth = panelWidth - PADDING * 2;
        int viewHeight = viewHeight();
        double maxX = maxScrollX(viewWidth);
        double maxY = maxScrollY(viewHeight);
        // 滚动条可以直接抓：点轨道会把滑块跳过去，随后继续拖动。
        if (maxY > 0.5D && mouseX >= viewX + viewWidth - SCROLL_BAR_HIT && mouseX <= viewX + viewWidth
            && mouseY >= viewY && mouseY <= viewY + viewHeight) {
            int thumb = verticalThumbLength(viewHeight);
            int travel = Math.max(1, viewHeight - thumb);
            double target = clamp((mouseY - viewY - thumb / 2.0D) / travel, 0.0D, 1.0D) * maxY;
            scrollY = target;
            draggingScrollY = true;
            scrollDragAnchor = mouseY;
            scrollDragStart = target;
            return true;
        }
        if (maxX > 0.5D && mouseY >= viewY + viewHeight - SCROLL_BAR_HIT && mouseY <= viewY + viewHeight
            && mouseX >= viewX && mouseX <= viewX + viewWidth) {
            int thumb = horizontalThumbLength(viewWidth);
            int travel = Math.max(1, viewWidth - thumb);
            double target = clamp((mouseX - viewX - thumb / 2.0D) / travel, 0.0D, 1.0D) * maxX;
            scrollX = target;
            draggingScrollX = true;
            scrollDragAnchor = mouseX;
            scrollDragStart = target;
            return true;
        }
        boolean canPan = maxX > 0.5D || maxY > 0.5D;
        if (!canPan || !inRect(mouseX, mouseY, viewX, viewY, viewWidth, viewHeight)) {
            draggingPanel = true;
            dragAnchorX = mouseX;
            dragAnchorY = mouseY;
            dragPanelX = panelX;
            dragPanelY = panelY;
            return true;
        }
        draggingContent = true;
        dragAnchorX = mouseX;
        dragAnchorY = mouseY;
        dragScrollX = scrollX;
        dragScrollY = scrollY;
        return true;
    }

    /**
     * 命中四边/四角的缩放区域时返回位掩码：1 左、2 右、4 上、8 下，否则 0。
     */
    private int resizeMaskAt(double mouseX, double mouseY) {
        int mask = 0;
        if (mouseX <= panelX + RESIZE_BAND) {
            mask |= 1;
        }
        if (mouseX >= panelX + panelWidth - RESIZE_BAND) {
            mask |= 2;
        }
        if (mouseY <= panelY + RESIZE_BAND) {
            mask |= 4;
        }
        if (mouseY >= panelY + panelHeight - RESIZE_BAND) {
            mask |= 8;
        }
        return mask;
    }

    /**
     * 处理拖动，返回是否消费该事件。
     *
     * <p>按下时若面板还在、拖动途中面板被关闭（例如点了「取消」），残余的拖动仍然吞掉，
     * 避免把半截拖动传给下层的终端界面。</p>
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (resizing && active()) {
            resizeTo(mouseX, mouseY);
            return true;
        }
        if (draggingPanel && active()) {
            Minecraft minecraft = Minecraft.getInstance();
            int screenWidth = minecraft.getWindow().getGuiScaledWidth();
            int screenHeight = minecraft.getWindow().getGuiScaledHeight();
            requestedX = clampInt(dragPanelX + (int) Math.round(mouseX - dragAnchorX), 0, screenWidth - panelWidth);
            requestedY = clampInt(dragPanelY + (int) Math.round(mouseY - dragAnchorY), 0, screenHeight - panelHeight);
            ensurePlaced();
            return true;
        }
        if (draggingScrollY && active()) {
            int viewHeight = viewHeight();
            double maxY = maxScrollY(viewHeight);
            int thumb = verticalThumbLength(viewHeight);
            int travel = Math.max(1, viewHeight - thumb);
            scrollY = clamp(scrollDragStart + (mouseY - scrollDragAnchor) * maxY / travel, 0.0D, maxY);
            return true;
        }
        if (draggingScrollX && active()) {
            int viewWidth = panelWidth - PADDING * 2;
            double maxX = maxScrollX(viewWidth);
            int thumb = horizontalThumbLength(viewWidth);
            int travel = Math.max(1, viewWidth - thumb);
            scrollX = clamp(scrollDragStart + (mouseX - scrollDragAnchor) * maxX / travel, 0.0D, maxX);
            return true;
        }
        if (draggingContent && active()) {
            int viewWidth = panelWidth - PADDING * 2;
            int viewHeight = viewHeight();
            scrollX = clamp(dragScrollX - (mouseX - dragAnchorX) / scale, 0.0D, maxScrollX(viewWidth));
            scrollY = clamp(dragScrollY - (mouseY - dragAnchorY) / scale, 0.0D, maxScrollY(viewHeight));
            return true;
        }
        return pressConsumed;
    }

    private void resizeTo(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int minWidth = Math.min(AutoCraftClientConfig.MIN_WIDTH, screenWidth);
        int minHeight = Math.min(AutoCraftClientConfig.MIN_HEIGHT, screenHeight);
        int deltaX = (int) Math.round(mouseX - dragAnchorX);
        int deltaY = (int) Math.round(mouseY - dragAnchorY);

        int newX = resizeStartX;
        int newY = resizeStartY;
        int newWidth = resizeStartWidth;
        int newHeight = resizeStartHeight;

        if ((resizeMask & 1) != 0) {
            newX = clampInt(resizeStartX + deltaX, 0, resizeStartX + resizeStartWidth - minWidth);
            newWidth = resizeStartX + resizeStartWidth - newX;
        } else if ((resizeMask & 2) != 0) {
            newWidth = clampInt(resizeStartWidth + deltaX, minWidth, screenWidth - resizeStartX);
        }
        if ((resizeMask & 4) != 0) {
            newY = clampInt(resizeStartY + deltaY, 0, resizeStartY + resizeStartHeight - minHeight);
            newHeight = resizeStartY + resizeStartHeight - newY;
        } else if ((resizeMask & 8) != 0) {
            newHeight = clampInt(resizeStartHeight + deltaY, minHeight, screenHeight - resizeStartY);
        }

        if ((resizeMask & 3) != 0) {
            requestedX = newX;
            requestedWidth = newWidth;
        }
        if ((resizeMask & 12) != 0) {
            requestedY = newY;
            requestedHeight = newHeight;
        }
        ensurePlaced();
    }

    /**
     * 处理鼠标释放，返回是否消费该事件。
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        boolean consuming = pressConsumed || draggingPanel || draggingContent || draggingScrollX || draggingScrollY || resizing;
        pressConsumed = false;
        draggingPanel = false;
        draggingContent = false;
        draggingScrollX = false;
        draggingScrollY = false;
        resizing = false;
        resizeMask = 0;
        return consuming || inIgnoreWindow(mouseX, mouseY);
    }

    /**
     * 处理滚轮，返回是否消费该事件。
     *
     * <p>按住 Ctrl 时缩放树（放大后超出的部分用滚动查看），否则按普通滚轮滚动。</p>
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY, boolean horizontal) {
        if (!active() || layoutPreview) {
            return false;
        }
        ensurePlaced();
        int viewX = panelX + PADDING;
        int viewY = panelY + HEADER;
        int viewWidth = panelWidth - PADDING * 2;
        int viewHeight = viewHeight();
        if (!inRect(mouseX, mouseY, viewX, viewY, viewWidth, viewHeight)) {
            return false;
        }
        if (Screen.hasControlDown()) {
            double treeX = (mouseX - viewX - axisOffset(viewWidth, contentWidth, scrollX)) / scale;
            double treeY = (mouseY - viewY - axisOffset(viewHeight, contentHeight, scrollY)) / scale;
            float factor = deltaY > 0.0D ? ZOOM_STEP : 1.0F / ZOOM_STEP;
            zoom = (float) clamp(zoom * factor, MIN_SCALE, MAX_SCALE / MIN_AUTOFIT_SCALE);
            ensurePlaced();
            scrollX = clamp(treeX - (mouseX - viewX) / scale, 0.0D, maxScrollX(viewWidth));
            scrollY = clamp(treeY - (mouseY - viewY) / scale, 0.0D, maxScrollY(viewHeight));
            return true;
        }
        if (horizontal || Screen.hasShiftDown() || maxScrollY(viewHeight) <= 0.5D) {
            scrollX = clamp(scrollX - deltaY * 12.0D / scale, 0.0D, maxScrollX(viewWidth));
        } else {
            scrollY = clamp(scrollY - deltaY * 12.0D / scale, 0.0D, maxScrollY(viewHeight));
        }
        return true;
    }

    private void toggleOnlyMissing() {
        onlyMissing = !onlyMissing;
        showingMissingAutomatically = false;
        scrollX = 0.0D;
        scrollY = 0.0D;
        rebuild();
        ensurePlaced();
        centerRoot();
    }

    private void centerRoot() {
        int[] position = positions.get(root);
        if (position != null) {
            int viewWidth = panelWidth - PADDING * 2;
            scrollX = clamp(position[0] + NODE / 2.0 - viewWidth / (2.0 * scale), 0.0D, maxScrollX(viewWidth));
        }
    }

    private int axisOffset(int viewport, int content, double scroll) {
        int scaled = Math.round(content * scale);
        return scaled <= viewport ? (viewport - scaled) / 2 : -Math.round((float) scroll * scale);
    }

    private boolean canSendToGrid() {
        return storageKind == StorageKind.HYPERDIMENSION && quantity > 1 && !structurePacking && !pickBlock;
    }

    private void tryConfirm(boolean toGrid) {
        if (!confirmEnabled || !validQuantityInput || busy) {
            return;
        }
        busy = true;
        Consumer<Boolean> callback = onConfirm;
        hide();
        ignoreInputBefore = System.currentTimeMillis() + IGNORE_INPUT_MS;
        callback.accept(toGrid);
    }

    private void cancel() {
        Runnable callback = onCancel;
        hide();
        ignoreInputBefore = System.currentTimeMillis() + IGNORE_INPUT_MS;
        callback.run();
    }

    /**
     * 面板刚被自己的按钮关掉时，短时间内仍然吞掉落在原面板范围内的点击。
     *
     * <p>否则双击「开始合成」的第二下会穿透到下层终端界面，把物品抓到光标上。</p>
     */
    private boolean inIgnoreWindow(double mouseX, double mouseY) {
        if (System.currentTimeMillis() >= ignoreInputBefore) {
            return false;
        }
        return inRect(mouseX, mouseY, panelX, panelY, panelWidth, panelHeight);
    }

    private void ensurePlaced() {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int previousX = panelX;
        int previousY = panelY;
        int previousWidth = panelWidth;
        int previousHeight = panelHeight;
        panelWidth = Math.min(requestedWidth, screenWidth);
        panelHeight = Math.min(requestedHeight, screenHeight);
        int defaultX = screenWidth - panelWidth - 12;
        int defaultY = (screenHeight - panelHeight) / 2;
        panelX = clampInt(requestedX == AutoCraftClientConfig.AUTO_POSITION ? defaultX : requestedX, 0, screenWidth - panelWidth);
        panelY = clampInt(requestedY == AutoCraftClientConfig.AUTO_POSITION ? defaultY : requestedY, 0, screenHeight - panelHeight);

        if (quantityInput != null && (footer == null || panelX != previousX || panelY != previousY
            || panelWidth != previousWidth || panelHeight != previousHeight)) {
            footer = createFooter();
            layoutQuantityInput(footer.quantity());
            if (windowChrome == null && ModList.get().isLoaded("anvillib_ui")) {
                windowChrome = new AnvilLibWindow();
            }
            if (windowChrome != null) {
                windowChrome.layout(panelX, panelY, panelWidth, panelHeight, footer.confirm(), footer.filter(), footer.cancel());
            }
        }

        if (root < 0 || contentWidth <= 0 || contentHeight <= 0) {
            scale = 1.0F;
        } else {
            int viewWidth = panelWidth - PADDING * 2;
            int viewHeight = viewHeight();
            float fit = Math.min(1.0F, Math.min(
                (float) (viewWidth - TREE_INSET * 2) / contentWidth,
                (float) (viewHeight - TREE_INSET * 2) / contentHeight
            ));
            scale = (float) clamp(Math.max(MIN_AUTOFIT_SCALE, fit) * zoom, MIN_SCALE, MAX_SCALE);
        }
    }

    private int viewHeight() {
        return viewHeight(statusLines(Minecraft.getInstance().font).size());
    }

    private int viewHeight(int statusLineCount) {
        return Math.max(1, panelHeight - HEADER - FOOTER - LEGEND_HEIGHT - PADDING - statusLineCount * 10);
    }

    private void rebuild() {
        root = -1;
        Map<Integer, List<Integer>> allChildren = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            int parent = nodes.get(index).parent();
            if (parent < 0) {
                if (root < 0) {
                    root = index;
                }
                continue;
            }
            allChildren.computeIfAbsent(parent, key -> new ArrayList<>()).add(index);
        }

        boolean[] keep = new boolean[nodes.size()];
        boolean anyMissing = false;
        if (onlyMissing) {
            for (int index = 0; index < nodes.size(); index++) {
                if (needsAttention(nodes.get(index))) {
                    anyMissing = true;
                    break;
                }
            }
            if (!anyMissing) {
                for (int index = 0; index < nodes.size(); index++) {
                    keep[index] = true;
                }
            } else if (root >= 0) {
                markMissingBranch(root, allChildren, keep);
            }
        } else {
            for (int index = 0; index < nodes.size(); index++) {
                keep[index] = true;
            }
        }

        Map<Integer, List<Integer>> childMap = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            if (!keep[index]) {
                continue;
            }
            int parent = nodes.get(index).parent();
            if (parent < 0) {
                if (root < 0) {
                    root = index;
                }
                continue;
            }
            if (keep[parent]) {
                childMap.computeIfAbsent(parent, key -> new ArrayList<>()).add(index);
            }
        }
        children = childMap;
        positions = new HashMap<>();
        widths = new HashMap<>();
        if (root < 0 || nodes.isEmpty()) {
            contentWidth = 0;
            contentHeight = 0;
            return;
        }
        contentWidth = subtreeWidth(root);
        int depth = place(root, 0, 0);
        contentHeight = depth * (NODE + GAP_Y) + NODE;
    }

    /**
     * 需要关注的节点：库存不足、要靠合成补齐（橙色），或者补不齐（红色）。
     */
    private static boolean needsAttention(CraftNodePayload node) {
        return node.missing() || node.crafts() > 0;
    }

    /**
     * 标记通往缺失物品的分支：节点自身需要关注、或它的子树里有需要关注的节点，就保留。
     */
    private boolean markMissingBranch(int index, Map<Integer, List<Integer>> allChildren, boolean[] keep) {
        boolean missing = needsAttention(nodes.get(index));
        List<Integer> kids = allChildren.get(index);
        if (kids != null) {
            for (int child : kids) {
                missing |= markMissingBranch(child, allChildren, keep);
            }
        }
        if (missing) {
            keep[index] = true;
        }
        return missing;
    }

    private int subtreeWidth(int index) {
        Integer cached = widths.get(index);
        if (cached != null) {
            return cached;
        }
        List<Integer> kids = children.get(index);
        int width;
        if (kids == null || kids.isEmpty()) {
            width = NODE;
        } else {
            int sum = 0;
            for (int child : kids) {
                sum += subtreeWidth(child);
            }
            sum += GAP_X * (kids.size() - 1);
            width = Math.max(NODE, sum);
        }
        widths.put(index, width);
        return width;
    }

    private int place(int index, int left, int depth) {
        List<Integer> kids = children.get(index);
        int width = subtreeWidth(index);
        positions.put(index, new int[]{left + (width - NODE) / 2, depth * (NODE + GAP_Y)});
        int deepest = depth;
        if (kids != null && !kids.isEmpty()) {
            int cursor = left;
            for (int child : kids) {
                deepest = Math.max(deepest, place(child, cursor, depth + 1));
                cursor += subtreeWidth(child) + GAP_X;
            }
        }
        return deepest;
    }

    private double maxScrollX(int viewWidth) {
        return Math.max(0.0D, contentWidth - (double) viewWidth / scale);
    }

    private double maxScrollY(int viewHeight) {
        return Math.max(0.0D, contentHeight - (double) viewHeight / scale);
    }

    private static boolean inRect(double mouseX, double mouseY, Rect2i bounds) {
        return inRect(mouseX, mouseY, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
