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
import dev.remold.core.RecipeUsage;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecipeBrowserScreen extends Screen {
    private final EditorScreen editor;
    private String query;
    private boolean changesOnly;
    private EditBox search;
    private RowList list;
    private Button edit, remove, revert, undo, uses, lock;
    @Nullable private Row selected;

    record Row(ResourceLocation id, @Nullable RecipeHolder<?> holder, ItemStack icon, String type, @Nullable ChangeEntry change, String search) {
        Row(ResourceLocation id, @Nullable RecipeHolder<?> holder, ItemStack icon, String type, @Nullable ChangeEntry change) {
            this(id, holder, icon, type, change, (id + " " + type + " " + icon.getHoverName().getString() + " "
                    + BuiltInRegistries.ITEM.getKey(icon.getItem())).toLowerCase(Locale.ROOT));
        }
    }

    @Nullable private List<Row> cachedRows;

    public RecipeBrowserScreen(EditorScreen editor, String query) {
        super(RemoldText.tr("browser.title"));
        this.editor = editor;
        this.query = query;
    }

    enum Mode { ALL, MAKES, USES }

    private Mode mode = Mode.ALL;

    public static RecipeBrowserScreen usesOf(EditorScreen editor, ItemStack item) {
        RecipeBrowserScreen screen = new RecipeBrowserScreen(editor, BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
        screen.mode = Mode.USES;
        return screen;
    }

    @Override
    protected void init() {
        int x0 = width / 2 - 182;
        search = new EditBox(font, x0, 24, 176, 16, RemoldText.tr("search"));
        search.setHint(RemoldText.tr("browser.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            refresh();
        });
        addRenderableWidget(search);
        addRenderableWidget(CycleButton.<Mode>builder(m -> RemoldText.tr("browser.mode." + m.name().toLowerCase(Locale.ROOT)))
                .withValues(Mode.values()).withInitialValue(mode)
                .create(x0 + 180, 22, 100, 20, RemoldText.tr("browser.mode"), (b, v) -> {
                    mode = v;
                    refresh();
                })).setTooltip(Tooltip.create(RemoldText.tr("browser.mode.hint")));
        addRenderableWidget(CycleButton.onOffBuilder(changesOnly)
                .create(x0 + 284, 22, 80, 20, RemoldText.tr("browser.changes"), (b, v) -> {
                    changesOnly = v;
                    refresh();
                })).setTooltip(Tooltip.create(RemoldText.tr("browser.changes.hint")));

        list = new RowList(minecraft, width, height - 92, 46, 22);
        addRenderableWidget(list);

        int y = height - 30;
        int x = width / 2 - 184;
        int w = 44, step = 46;
        edit = addRenderableWidget(Button.builder(RemoldText.tr("browser.edit"), b -> editSelected()).bounds(x, y, w, 20).build());
        edit.setTooltip(Tooltip.create(RemoldText.tr("browser.edit.hint")));
        remove = addRenderableWidget(Button.builder(RemoldText.tr("browser.remove").withStyle(ChatFormatting.RED), b -> action(Payloads.Action.REMOVE)).bounds(x + step, y, w, 20).build());
        remove.setTooltip(Tooltip.create(RemoldText.tr("browser.remove.hint")));
        revert = addRenderableWidget(Button.builder(RemoldText.tr("browser.restore"), b -> action(Payloads.Action.REVERT)).bounds(x + step * 2, y, w, 20).build());
        revert.setTooltip(Tooltip.create(RemoldText.tr("browser.restore.hint")));
        uses = addRenderableWidget(Button.builder(RemoldText.tr("browser.uses"), b -> showUses()).bounds(x + step * 3, y, w, 20).build());
        uses.setTooltip(Tooltip.create(RemoldText.tr("browser.uses.hint")));
        lock = addRenderableWidget(Button.builder(RemoldText.tr("browser.lock"), b -> {
            if (selected != null && selected.holder() != null) minecraft.setScreen(new GateScreen(this, selected.holder()));
        }).bounds(x + step * 4, y, w, 20).build());
        lock.setTooltip(Tooltip.create(RemoldText.tr("browser.lock.hint")));
        undo = addRenderableWidget(Button.builder(RemoldText.tr("browser.undo"), b -> Services.PLATFORM.sendToServer(
                new Payloads.RecipeAction(Payloads.Action.UNDO, dev.remold.Remold.id("none"), !hasShiftDown()))).bounds(x + step * 5, y, w, 20).build());
        undo.setTooltip(Tooltip.create(RemoldText.tr("browser.undo.hint")));
        Button apply = addRenderableWidget(Button.builder(RemoldText.tr("browser.apply"), b -> Services.PLATFORM.sendToServer(Payloads.RecipeAction.simple(Payloads.Action.APPLY)))
                .bounds(x + step * 6, y, w, 20).build());
        apply.setTooltip(Tooltip.create(RemoldText.tr("browser.apply.hint")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x + step * 7, y, w, 20).build());
        lastHash = currentHash();
        refresh();
        setInitialFocus(search);
    }

    private void showUses() {
        if (selected == null || selected.holder() == null) return;
        mode = Mode.USES;
        query = BuiltInRegistries.ITEM.getKey(selected.icon().getItem()).toString();
        rebuildWidgets();
    }

    private Set<Item> matchingItems(String q) {
        ResourceLocation exact = ResourceLocation.tryParse(q);
        if (exact != null && q.contains(":") && BuiltInRegistries.ITEM.containsKey(exact)) {
            return Set.of(BuiltInRegistries.ITEM.get(exact));
        }
        Set<Item> items = new HashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().contains(q)
                    || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)) {
                items.add(item);
            }
        }
        return items;
    }

    private List<Row> allRows() {
        if (cachedRows == null) cachedRows = buildRows();
        return cachedRows;
    }

    private List<Row> buildRows() {
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

        for (ChangeEntry c : changes.values()) {
            rows.add(new Row(c.id(), null, new ItemStack(Items.BARRIER), c.recipeType(), c));
        }
        rows.sort(Comparator.comparing((Row r) -> r.change() == null).thenComparing(r -> r.id().toString()));
        return rows;
    }

    private void refresh() {
        String q = query.toLowerCase(Locale.ROOT).trim();
        Set<Item> items = mode != Mode.ALL && !q.isEmpty() ? matchingItems(q) : Set.of();
        List<Row> matches = allRows().stream().filter(r -> {
            if (changesOnly && r.change() == null) return false;
            if (q.isEmpty()) return mode == Mode.ALL;
            return switch (mode) {
                case MAKES -> r.holder() != null && items.contains(r.icon().getItem());
                case USES -> r.holder() != null && RecipeUsage.usesAny(r.holder().value(), items);
                case ALL -> r.search().contains(q);
            };
        }).toList();
        list.set(matches.size() > 2000 ? matches.subList(0, 2000) : matches);
        resultCount = matches.size();
        select(null);
    }

    private int resultCount;

    private void select(@Nullable Row row) {
        selected = row;
        edit.active = row != null && row.holder() != null;
        remove.active = row != null && row.holder() != null;
        revert.active = row != null && row.change() != null;
        uses.active = row != null && row.holder() != null;
        lock.active = row != null && row.holder() != null;
    }

    private void editSelected() {
        if (selected == null || selected.holder() == null) return;
        editor.load(selected.holder());
        minecraft.setScreen(editor);
    }

    private void action(Payloads.Action action) {
        if (selected == null) return;
        Payloads.RecipeAction payload = new Payloads.RecipeAction(action, selected.id(), !hasShiftDown());
        if (action == Payloads.Action.REMOVE && !hasAltDown()) {
            var lost = RemovalCheck.lost(minecraft, java.util.Set.of(selected.id()), java.util.Set.of());
            if (!lost.isEmpty()) {
                minecraft.setScreen(new WarningScreen(this, lost, () -> Services.PLATFORM.sendToServer(payload)));
                return;
            }
        }
        Services.PLATFORM.sendToServer(payload);
    }

    @Override
    public void tick() {
        super.tick();
        undo.active = ClientState.canUndo();

        int hash = currentHash();
        if (hash != lastHash) {
            lastHash = hash;
            cachedRows = null;
            ResourceLocation keep = selected == null ? null : selected.id();
            refresh();
            if (keep != null) list.children().stream().filter(e -> e.row.id().equals(keep)).findFirst().ifPresent(e -> {
                list.setSelected(e);
                select(e.row);
            });
        }
    }

    private int lastHash;

    private int currentHash() {
        return ClientState.changes().hashCode() ^ System.identityHashCode(minecraft.level.getRecipeManager().getRecipes());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawString(font, RemoldText.tr("browser.count", resultCount), width / 2 - 182, height - 42, 0x808080);
        if (ClientState.pendingApply()) {
            var pending = RemoldText.tr("browser.pending").withStyle(ChatFormatting.YELLOW);
            g.drawString(font, pending, width / 2 + 182 - font.width(pending), height - 42, 0xFFFFFF);
        }
    }

    @org.jetbrains.annotations.Nullable private Screen back;

    public RecipeBrowserScreen backTo(Screen screen) {
        back = screen;
        return this;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back != null ? back : editor);
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
                String gate = ClientState.gate(row.id());
                if (gate != null) dev.remold.client.widget.UiDraw.padlock(g, left + width - 12, top + 1, !ClientState.isUnlocked(gate));
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
