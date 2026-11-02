package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.ClientState;
import dev.remold.data.ChangeEntry;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Search all recipes (from every mod), and edit, remove or restore them. */
public class RecipeBrowserScreen extends Screen {
    private final EditorScreen editor;
    private String query;
    private boolean changesOnly;
    private EditBox search;
    private RowList list;
    private Button edit, remove, revert, undo;
    @Nullable private Row selected;

    record Row(ResourceLocation id, @Nullable RecipeHolder<?> holder, ItemStack icon, String type, @Nullable ChangeEntry change) {}

    public RecipeBrowserScreen(EditorScreen editor, String query) {
        super(RemoldText.tr("browser.title"));
        this.editor = editor;
        this.query = query;
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 150, 22, 196, 16, RemoldText.tr("search"));
        search.setHint(RemoldText.tr("browser.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            refresh();
        });
        addRenderableWidget(search);
        addRenderableWidget(CycleButton.onOffBuilder(changesOnly)
                .create(width / 2 + 50, 20, 100, 20, RemoldText.tr("browser.changes"), (b, v) -> {
                    changesOnly = v;
                    refresh();
                })).setTooltip(Tooltip.create(RemoldText.tr("browser.changes.hint")));

        list = new RowList(minecraft, width, height - 80, 44, 22);
        addRenderableWidget(list);

        int y = height - 30;
        int x = width / 2 - 155;
        edit = addRenderableWidget(Button.builder(RemoldText.tr("browser.edit"), b -> editSelected()).bounds(x, y, 50, 20).build());
        edit.setTooltip(Tooltip.create(RemoldText.tr("browser.edit.hint")));
        remove = addRenderableWidget(Button.builder(RemoldText.tr("browser.remove").withStyle(ChatFormatting.RED), b -> action(Payloads.Action.REMOVE)).bounds(x + 52, y, 50, 20).build());
        remove.setTooltip(Tooltip.create(RemoldText.tr("browser.remove.hint")));
        revert = addRenderableWidget(Button.builder(RemoldText.tr("browser.restore"), b -> action(Payloads.Action.REVERT)).bounds(x + 104, y, 50, 20).build());
        revert.setTooltip(Tooltip.create(RemoldText.tr("browser.restore.hint")));
        undo = addRenderableWidget(Button.builder(RemoldText.tr("browser.undo"), b -> Services.PLATFORM.sendToServer(
                new Payloads.RecipeAction(Payloads.Action.UNDO, dev.remold.Remold.id("none"), !hasShiftDown()))).bounds(x + 156, y, 50, 20).build());
        undo.setTooltip(Tooltip.create(RemoldText.tr("browser.undo.hint")));
        Button apply = addRenderableWidget(Button.builder(RemoldText.tr("browser.apply"), b -> Services.PLATFORM.sendToServer(Payloads.RecipeAction.simple(Payloads.Action.APPLY)))
                .bounds(x + 208, y, 50, 20).build());
        apply.setTooltip(Tooltip.create(RemoldText.tr("browser.apply.hint")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x + 260, y, 50, 20).build());
        refresh();
        setInitialFocus(search);
    }

    private List<Row> allRows() {
        var level = minecraft.level;
        Map<ResourceLocation, ChangeEntry> changes = new HashMap<>();
        for (ChangeEntry c : ClientState.changes()) changes.put(c.id(), c);
        List<Row> rows = new ArrayList<>();
        for (RecipeHolder<?> h : level.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = h.value().getResultItem(level.registryAccess());
            } catch (Exception e) {
                result = ItemStack.EMPTY;
            }
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType());
            rows.add(new Row(h.id(), h, result.isEmpty() ? new ItemStack(Items.PAPER) : result, String.valueOf(type), changes.remove(h.id())));
        }
        // Removed recipes are no longer loaded, but must stay visible so they can be restored.
        for (ChangeEntry c : changes.values()) {
            rows.add(new Row(c.id(), null, new ItemStack(Items.BARRIER), c.recipeType(), c));
        }
        rows.sort(Comparator.comparing((Row r) -> r.change() == null).thenComparing(r -> r.id().toString()));
        return rows;
    }

    private void refresh() {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Row> rows = allRows().stream().filter(r -> {
            if (changesOnly && r.change() == null) return false;
            if (q.isEmpty()) return true;
            return r.id().toString().contains(q) || r.type().contains(q)
                    || r.icon().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BuiltInRegistries.ITEM.getKey(r.icon().getItem()).toString().contains(q);
        }).limit(2000).toList();
        list.set(rows);
        select(null);
    }

    private void select(@Nullable Row row) {
        selected = row;
        edit.active = row != null && row.holder() != null;
        remove.active = row != null && row.holder() != null;
        revert.active = row != null && row.change() != null;
    }

    private void editSelected() {
        if (selected == null || selected.holder() == null) return;
        editor.load(selected.holder());
        minecraft.setScreen(editor);
    }

    private void action(Payloads.Action action) {
        if (selected == null) return;
        Services.PLATFORM.sendToServer(new Payloads.RecipeAction(action, selected.id(), !hasShiftDown()));
    }

    @Override
    public void tick() {
        super.tick();
        undo.active = ClientState.canUndo();
        // Rows change once the server has reloaded; refresh when the data we're based on changes.
        int hash = ClientState.changes().hashCode() ^ System.identityHashCode(minecraft.level.getRecipeManager().getRecipes());
        if (hash != lastHash) {
            lastHash = hash;
            ResourceLocation keep = selected == null ? null : selected.id();
            refresh();
            if (keep != null) list.children().stream().filter(e -> e.row.id().equals(keep)).findFirst().ifPresent(e -> {
                list.setSelected(e);
                select(e.row);
            });
        }
    }

    private int lastHash;

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        if (ClientState.pendingApply()) {
            g.drawCenteredString(font, RemoldText.tr("browser.pending").withStyle(ChatFormatting.YELLOW), width / 2, height - 42, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(editor);
    }

    class RowList extends ObjectSelectionList<RowList.Entry> {
        RowList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set(List<Row> rows) {
            replaceEntries(rows.stream().map(Entry::new).toList());
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return 310;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final Row row;
            private long lastClick;

            Entry(Row row) {
                this.row = row;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                g.renderItem(row.icon(), left + 2, top + 2);
                int textX = left + 22;
                if (row.change() != null) {
                    ChangeEntry.Kind kind = row.change().kind();
                    String badge = switch (kind) {
                        case ADDED -> "+";
                        case REPLACED -> "*";
                        case REMOVED -> "-";
                    };
                    int color = switch (kind) {
                        case ADDED -> 0x55FF55;
                        case REPLACED -> 0xFFFF55;
                        case REMOVED -> 0xFF5555;
                    };
                    g.drawString(font, badge, textX, top + 1, color);
                    textX += 8;
                }
                g.drawString(font, font.plainSubstrByWidth(row.id().toString(), width - (textX - left) - 4), textX, top + 1, row.holder() == null ? 0x909090 : 0xFFFFFF);
                String sub = row.type() + "  " + (row.holder() == null ? RemoldText.tr("browser.removed").getString() : "-> " + row.icon().getHoverName().getString());
                if (row.change() != null && !row.change().author().isEmpty()) sub += "  " + RemoldText.tr("browser.by", row.change().author()).getString();
                g.drawString(font, font.plainSubstrByWidth(sub, width - 26), left + 22, top + 11, 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                list.setSelected(this);
                select(row);
                long now = net.minecraft.Util.getMillis();
                if (now - lastClick < 250) editSelected();
                lastClick = now;
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(row.id().toString());
            }
        }
    }
}
