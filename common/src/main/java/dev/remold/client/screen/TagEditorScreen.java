package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.ClientState;
import dev.remold.client.widget.UiDraw;
import dev.remold.core.GhostIngredient;
import dev.remold.data.TagEdit;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TagEditorScreen extends Screen {
    private final Map<ResourceLocation, Draft> drafts = new HashMap<>();
    private final Screen parent;
    private String query = "";
    @Nullable private ResourceLocation selected;
    private TagList list;
    private Button create, save;
    private int scroll;
    private final List<String> shown = new ArrayList<>();

    private static final class Draft {
        final LinkedHashSet<String> add, remove;
        boolean dirty;

        Draft(TagEdit edit) {
            add = new LinkedHashSet<>(edit.add());
            remove = new LinkedHashSet<>(edit.remove());
        }

        TagEdit toEdit() {
            return new TagEdit(List.copyOf(add), List.copyOf(remove));
        }
    }

    public TagEditorScreen(Screen parent) {
        super(RemoldText.tr("tags.title"));
        this.parent = parent;
    }

    private Draft draft(ResourceLocation tag) {
        return drafts.computeIfAbsent(tag, t -> new Draft(ClientState.tagEdits().getOrDefault(t, TagEdit.EMPTY)));
    }

    private int half() {
        return width / 2;
    }

    @Override
    protected void init() {
        EditBox search = new EditBox(font, 10, 24, half() - 80, 16, RemoldText.tr("search"));
        search.setHint(RemoldText.tr("tags.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(s -> {
            query = s.trim().toLowerCase(Locale.ROOT);
            refreshList();
        });
        addRenderableWidget(search);
        create = addRenderableWidget(Button.builder(RemoldText.tr("tags.create"), b -> {
            ResourceLocation id = newTagId();
            if (id != null) select(id);
        }).bounds(half() - 66, 22, 60, 20).build());
        create.setTooltip(Tooltip.create(RemoldText.tr("tags.create.hint")));

        list = new TagList(minecraft, half() - 10, height - 84, 46, 14);
        list.setX(5);
        addRenderableWidget(list);

        Button add = addRenderableWidget(Button.builder(RemoldText.tr("tags.add"), b -> minecraft.setScreen(new ItemPickerScreen(this, stack -> {
            if (selected == null) return;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Draft d = draft(selected);
            if (!d.remove.remove(id)) d.add.add(id);
            d.dirty = true;
        }))).bounds(half() + 5, 22, 90, 20).build());
        add.active = selected != null;
        add.setTooltip(Tooltip.create(RemoldText.tr("tags.add.hint")));

        save = addRenderableWidget(Button.builder(RemoldText.tr("tags.save").withStyle(ChatFormatting.GREEN), b -> saveAll(!hasShiftDown()))
                .bounds(width / 2 - 154, height - 28, 100, 20).build());
        save.setTooltip(Tooltip.create(RemoldText.tr("tags.save.hint")));
        Button reset = addRenderableWidget(Button.builder(RemoldText.tr("tags.reset"), b -> {
            if (selected == null) return;
            Draft d = draft(selected);
            d.add.clear();
            d.remove.clear();
            d.dirty = true;
        }).bounds(width / 2 - 50, height - 28, 100, 20).build());
        reset.setTooltip(Tooltip.create(RemoldText.tr("tags.reset.hint")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 54, height - 28, 100, 20).build());
        refreshList();
    }

    @Nullable
    private ResourceLocation newTagId() {
        String q = query.startsWith("#") ? query.substring(1) : query;
        if (q.isEmpty()) return null;
        ResourceLocation id = ResourceLocation.tryParse(q.contains(":") ? q : "c:" + q);
        if (id == null || BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).isPresent()) return null;
        return id;
    }

    private void refreshList() {
        Set<ResourceLocation> tags = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        BuiltInRegistries.ITEM.getTagNames().forEach(t -> tags.add(t.location()));
        tags.addAll(ClientState.tagEdits().keySet());
        tags.addAll(drafts.keySet());
        String q = query.startsWith("#") ? query.substring(1) : query;
        List<ResourceLocation> filtered = tags.stream().filter(t -> q.isEmpty() || t.toString().contains(q)).toList();
        list.set(filtered);
        create.active = newTagId() != null;
    }

    private void select(ResourceLocation tag) {
        selected = tag;
        scroll = 0;
        rebuildWidgets();
    }

    private void saveAll(boolean apply) {
        List<Map.Entry<ResourceLocation, Draft>> dirty = drafts.entrySet().stream().filter(e -> e.getValue().dirty).toList();
        for (int i = 0; i < dirty.size(); i++) {
            var e = dirty.get(i);

            Services.PLATFORM.sendToServer(new Payloads.SetTag(e.getKey(), e.getValue().toEdit(), apply && i == dirty.size() - 1));
            e.getValue().dirty = false;
        }
    }

    private int gridX() {
        return half() + 5;
    }

    private int gridY() {
        return 50;
    }

    private int cols() {
        return Math.max(1, (width - gridX() - 10) / 18);
    }

    private int rows() {
        return Math.max(1, (height - 64 - gridY()) / 18);
    }

    private void computeShown() {
        shown.clear();
        if (selected == null) return;
        LinkedHashSet<String> all = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, selected)).ifPresent(set ->
                set.forEach(h -> h.unwrapKey().ifPresent(k -> all.add(k.location().toString()))));
        Draft d = draft(selected);
        all.addAll(d.add);
        all.addAll(d.remove);
        shown.addAll(all);
    }

    private ItemStack stackOf(String entry) {
        if (entry.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(entry.substring(1));
            if (id == null) return new ItemStack(Items.BARRIER);
            return GhostIngredient.tagItems(TagKey.create(Registries.ITEM, id)).stream().findFirst()
                    .map(ItemStack::new).orElse(new ItemStack(Items.BARRIER));
        }
        ResourceLocation id = ResourceLocation.tryParse(entry);
        Item item = id == null ? Items.BARRIER : BuiltInRegistries.ITEM.get(id);
        return new ItemStack(item);
    }

    private int entryAt(double mouseX, double mouseY) {
        int c = (int) Math.floor((mouseX - gridX()) / 18), r = (int) Math.floor((mouseY - gridY()) / 18);
        if (mouseX < gridX() || mouseY < gridY() || c >= cols() || r >= rows()) return -1;
        int i = (scroll + r) * cols() + c;
        return i < shown.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = entryAt(mouseX, mouseY);
        if (i >= 0 && selected != null) {
            String entry = shown.get(i);
            Draft d = draft(selected);
            if (d.add.remove(entry)) {
            } else if (!d.remove.remove(entry)) {
                d.remove.add(entry);
            }
            d.dirty = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= gridX()) {
            int maxScroll = Math.max(0, (shown.size() + cols() - 1) / cols() - rows());
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        save.active = drafts.values().stream().anyMatch(d -> d.dirty);
        if (selected == null) {
            g.drawString(font, RemoldText.tr("tags.none"), gridX(), gridY() + 4, 0x808080);
            return;
        }
        computeShown();
        g.drawString(font, font.plainSubstrByWidth("#" + selected, width - gridX() - 100), gridX() + 96, 28, 0xFFFF55);
        Draft d = draft(selected);
        int cols = cols(), rows = rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scroll + r) * cols + c;
                if (i >= shown.size()) break;
                String entry = shown.get(i);
                int x = gridX() + c * 18, y = gridY() + r * 18;
                UiDraw.slot(g, x, y, 18);
                g.renderItem(stackOf(entry), x + 1, y + 1);
                if (d.remove.contains(entry)) {
                    g.fill(x + 1, y + 1, x + 17, y + 17, 0xA0AA0000);
                } else if (d.add.contains(entry)) {
                    g.renderOutline(x, y, 18, 18, 0xFF55FF55);
                }
                if (entry.startsWith("#")) g.drawString(font, "#", x + 1, y + 1, 0xFFFFAA00, true);
            }
        }
        var legend = font.split(RemoldText.tr("tags.legend"), width - gridX() - 8);
        for (int i = 0; i < legend.size(); i++) {
            g.drawString(font, legend.get(i), gridX(), height - 40 - (legend.size() - 1 - i) * 10, 0x808080);
        }
        int hovered = entryAt(mouseX, mouseY);
        if (hovered >= 0) {
            String entry = shown.get(hovered);
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stackOf(entry)));
            lines.add(Component.literal(entry).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(RemoldText.tr(d.remove.contains(entry) ? "tags.entry.removed"
                    : d.add.contains(entry) ? "tags.entry.added" : "tags.entry.normal").withStyle(ChatFormatting.GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class TagList extends ObjectSelectionList<TagList.Entry> {
        TagList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set(List<ResourceLocation> tags) {
            replaceEntries(tags.stream().map(Entry::new).toList());
            children().stream().filter(e -> e.tag.equals(selected)).findFirst().ifPresent(this::setSelected);
        }

        @Override
        public int getRowWidth() {
            return width - 20;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final ResourceLocation tag;

            Entry(ResourceLocation tag) {
                this.tag = tag;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean edited = ClientState.tagEdits().containsKey(tag) || (drafts.containsKey(tag) && drafts.get(tag).dirty);
                g.drawString(font, font.plainSubstrByWidth("#" + tag, width - 4), left + 2, top + 2, edited ? 0x55FF55 : 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                select(tag);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal("#" + tag);
            }
        }
    }
}
