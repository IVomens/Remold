package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

public class HomeScreen extends Screen {
    private record Tile(Item icon, String key, Supplier<Screen> open) {}

    @Nullable private final Screen parent;
    private EditorScreen editor;
    private Button undo, apply;

    public HomeScreen(@Nullable Screen parent) {
        super(RemoldText.tr("home.title"));
        this.parent = parent;
    }

    EditorScreen editor() {
        if (editor == null) editor = RemoldClient.newEditor(this);
        return editor;
    }

    private List<Tile> tiles() {
        return List.of(
                new Tile(Items.CRAFTING_TABLE, "make", () -> {
                    editor().returnTo(null);
                    return editor();
                }),
                new Tile(Items.BOOK, "find", () -> new RecipeBrowserScreen(editor(), "").backTo(this)),
                new Tile(Items.WRITABLE_BOOK, "checklist", () -> new ChecklistScreen(this, editor())),
                new Tile(Items.PAPER, "files", () -> new FilesScreen(this, editor())),
                new Tile(Items.COPPER_INGOT, "unify", () -> new UnifyScreen(this)),
                new Tile(Items.NAME_TAG, "tags", () -> new TagEditorScreen(this)),
                new Tile(Items.OAK_SIGN, "tooltips", () -> new AnnotationScreen(this, ItemStack.EMPTY)),
                new Tile(Items.ANVIL, "bulk", () -> new BulkScreen(this)),
                new Tile(Items.CHEST, "import", () -> new ImportScreen(this)));
    }

    @Override
    protected void init() {
        List<Tile> tiles = tiles();
        int cols = width >= 420 ? 3 : 2;
        int gap = 4;
        int tileW = Math.min(150, (width - 16 - gap * (cols - 1)) / cols);
        int rows = (tiles.size() + cols - 1) / cols;
        int tileH = Math.max(28, Math.min(46, (height - 74 - gap * (rows - 1)) / rows));
        int left = (width - (tileW * cols + gap * (cols - 1))) / 2;
        int top = 30;
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            int x = left + (i % cols) * (tileW + gap);
            int y = top + (i / cols) * (tileH + gap);
            TileButton button = new TileButton(x, y, tileW, tileH, tile, b -> minecraft.setScreen(tile.open().get()));
            button.setTooltip(Tooltip.create(RemoldText.tr("home." + tile.key() + ".hint")));
            addRenderableWidget(button);
        }
        int y = height - 28;
        undo = addRenderableWidget(Button.builder(RemoldText.tr("home.undo"), b ->
                Services.PLATFORM.sendToServer(new Payloads.RecipeAction(Payloads.Action.UNDO, dev.remold.Remold.id("none"), true)))
                .bounds(width / 2 - 154, y, 100, 20).build());
        undo.setTooltip(Tooltip.create(RemoldText.tr("home.undo.hint")));
        apply = addRenderableWidget(Button.builder(RemoldText.tr("browser.apply"), b ->
                Services.PLATFORM.sendToServer(Payloads.RecipeAction.simple(Payloads.Action.APPLY)))
                .bounds(width / 2 - 50, y, 100, 20).build());
        apply.setTooltip(Tooltip.create(RemoldText.tr("browser.apply.hint")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 54, y, 100, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        undo.active = ClientState.canUndo();
        apply.active = ClientState.pendingApply();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, RemoldText.tr("home.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        if (ClientState.pendingApply()) {
            g.drawCenteredString(font, RemoldText.tr("browser.pending").withStyle(ChatFormatting.YELLOW), width / 2, height - 40, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private class TileButton extends Button {
        private final Tile tile;

        TileButton(int x, int y, int w, int h, Tile tile, OnPress onPress) {
            super(x, y, w, h, RemoldText.tr("home." + tile.key()), onPress, DEFAULT_NARRATION);
            this.tile = tile;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Component saved = getMessage();
            setMessage(Component.empty());
            super.renderWidget(g, mouseX, mouseY, partialTick);
            setMessage(saved);
            Font font = HomeScreen.this.font;
            int iconY = getY() + (height - 16) / 2;
            g.renderItem(new ItemStack(tile.icon()), getX() + 6, iconY);
            int textX = getX() + 28;
            int textW = width - 32;
            List<FormattedCharSequence> desc = font.split(RemoldText.tr("home." + tile.key() + ".desc"), textW);
            int lines = height >= 40 ? Math.min(2, desc.size()) : 0;
            int textY = getY() + (height - 10 - lines * 9) / 2;
            g.drawString(font, font.plainSubstrByWidth(saved.getString(), textW), textX, textY, isHoveredOrFocused() ? 0xFFFF55 : 0xFFFFFF);
            for (int i = 0; i < lines; i++) {
                g.drawString(font, desc.get(i), textX, textY + 10 + i * 9, 0xB0B0B0);
            }
        }
    }
}
