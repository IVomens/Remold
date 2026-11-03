package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.Progress;
import dev.remold.client.widget.UiDraw;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChecklistScreen extends Screen {
    enum Filter { ALL, TODO, DONE, SKIPPED }

    private static String lastMod;
    private static Filter lastFilter = Filter.ALL;

    private final Screen parent;
    private final EditorScreen editor;
    @Nullable private String mod;
    private Filter filter = lastFilter;
    private List<Item> shown = List.of();
    private int scroll;
    private ModList list;

    public ChecklistScreen(Screen parent, EditorScreen editor) {
        super(RemoldText.tr("checklist.title"));
        this.parent = parent;
        this.editor = editor;
        this.mod = lastMod;
    }

    private int gridX() {
        return width / 2 - 20;
    }

    private int gridY() {
        return 58;
    }

    private int cols() {
        return Math.max(1, (width - gridX() - 10) / 20);
    }

    private int rows() {
        return Math.max(1, (height - 54 - gridY()) / 20);
    }

    @Override
    protected void init() {
        List<String> mods = Checklist.mods();
        if (mod == null || !mods.contains(mod)) mod = mods.stream().filter(m -> !m.equals("minecraft")).findFirst().orElse(mods.isEmpty() ? null : mods.getFirst());
        list = new ModList(minecraft, width / 2 - 30, height - 72, 34, 24);
        list.setX(5);
        list.set(mods);
        addRenderableWidget(list);
        addRenderableWidget(CycleButton.<Filter>builder(f -> RemoldText.tr("checklist.filter." + f.name().toLowerCase(Locale.ROOT)))
                .withValues(Filter.values()).withInitialValue(filter)
                .create(width - 110, 30, 100, 20, RemoldText.tr("checklist.show"), (b, v) -> {
                    filter = v;
                    lastFilter = v;
                    scroll = 0;
                })).setTooltip(Tooltip.create(RemoldText.tr("checklist.show.hint")));
        Button next = addRenderableWidget(Button.builder(RemoldText.tr("checklist.next").withStyle(ChatFormatting.GREEN), b -> {
            if (mod == null) return;
            Item item = Checklist.nextToDo(mod, null);
            if (item == null) {
                dev.remold.client.RemoldClient.toast(true, RemoldText.tr("checklist.all_done"), RemoldText.tr("checklist.all_done.message"));
            } else {
                open(item);
            }
        }).bounds(width / 2 - 154, height - 28, 150, 20).build());
        next.setTooltip(Tooltip.create(RemoldText.tr("checklist.next.hint")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    private void open(Item item) {
        editor.returnTo(this);
        editor.startChecklistItem(mod, item);
        minecraft.setScreen(editor);
    }

    private void computeShown() {
        List<Item> items = new ArrayList<>();
        if (mod != null) {
            for (Item item : Checklist.items(mod)) {
                Checklist.Status s = Checklist.status(item);
                boolean keep = switch (filter) {
                    case ALL -> true;
                    case TODO -> s == Checklist.Status.TODO;
                    case DONE -> s == Checklist.Status.DONE;
                    case SKIPPED -> s == Checklist.Status.SKIPPED;
                };
                if (keep) items.add(item);
            }
        }
        shown = items;
    }

    private int itemAt(double mouseX, double mouseY) {
        if (mouseX < gridX() || mouseY < gridY()) return -1;
        int c = (int) ((mouseX - gridX()) / 20), r = (int) ((mouseY - gridY()) / 20);
        if (c >= cols() || r >= rows()) return -1;
        int i = (scroll + r) * cols() + c;
        return i < shown.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = itemAt(mouseX, mouseY);
        if (i >= 0) {
            Item item = shown.get(i);
            String key = Progress.itemKey(BuiltInRegistries.ITEM.getKey(item).toString());
            Checklist.Status status = Checklist.status(item);
            if (button == 1) {
                Progress.setSkipped(key, status != Checklist.Status.SKIPPED);
            } else if (button == 0 && hasShiftDown()) {
                Progress.setDone(key, status != Checklist.Status.DONE);
            } else if (button == 0) {
                open(item);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= gridX()) {
            int max = Math.max(0, (shown.size() + cols() - 1) / cols() - rows());
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        g.drawCenteredString(font, RemoldText.tr("checklist.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        if (mod == null) return;
        computeShown();
        Checklist.Summary summary = Checklist.summary(mod);
        g.drawString(font, font.plainSubstrByWidth(Services.PLATFORM.modName(mod), width - gridX() - 120), gridX(), 32, 0xFFFF55);
        g.drawString(font, RemoldText.tr("checklist.progress", summary.done(), summary.total()), gridX(), 42, 0xC0C0C0);
        ProgressBar.draw(g, gridX() + 90, 43, Math.max(20, width - gridX() - 210), summary.done(), summary.total());
        int cols = cols(), rows = rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scroll + r) * cols + c;
                if (i >= shown.size()) break;
                int x = gridX() + c * 20, y = gridY() + r * 20;
                Item item = shown.get(i);
                UiDraw.slot(g, x, y, 20);
                g.renderItem(new ItemStack(item), x + 2, y + 2);
                switch (Checklist.status(item)) {
                    case DONE -> ProgressBar.tick(g, x, y);
                    case SKIPPED -> ProgressBar.dim(g, x, y);
                    default -> {}
                }
            }
        }
        if (shown.isEmpty()) {
            g.drawString(font, RemoldText.tr("checklist.empty"), gridX(), gridY() + 4, 0x808080);
        }
        var legend = font.split(RemoldText.tr("checklist.legend").withStyle(ChatFormatting.DARK_GRAY), width - gridX() - 8);
        for (int i = 0; i < legend.size(); i++) g.drawString(font, legend.get(i), gridX(), height - 40 - (legend.size() - 1 - i) * 10, 0xFFFFFF);
        int hovered = itemAt(mouseX, mouseY);
        if (hovered >= 0) {
            Item item = shown.get(hovered);
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, new ItemStack(item)));
            Checklist.Status status = Checklist.status(item);
            lines.add(RemoldText.tr("checklist.status." + status.name().toLowerCase(Locale.ROOT))
                    .withStyle(status == Checklist.Status.DONE ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            lines.add(RemoldText.tr(status == Checklist.Status.DONE ? "checklist.click.edit" : "checklist.click.make").withStyle(ChatFormatting.YELLOW));
            lines.add(RemoldText.tr("checklist.click.other").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class ModList extends ObjectSelectionList<ModList.Entry> {
        ModList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set(List<String> mods) {
            replaceEntries(mods.stream().map(Entry::new).toList());
            children().stream().filter(e -> e.mod.equals(mod)).findFirst().ifPresent(e -> {
                setSelected(e);
                centerScrollOn(e);
            });
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
            final String mod;
            private Checklist.Summary summary;
            private long computed;

            Entry(String mod) {
                this.mod = mod;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                long now = net.minecraft.Util.getMillis();
                if (summary == null || now - computed > 1000) {
                    summary = Checklist.summary(mod);
                    computed = now;
                }
                g.drawString(font, font.plainSubstrByWidth(Services.PLATFORM.modName(mod), width - 50), left + 2, top + 2, 0xFFFFFF);
                String count = summary.done() + "/" + summary.total();
                g.drawString(font, count, left + width - 4 - font.width(count), top + 2, summary.done() >= summary.total() && summary.total() > 0 ? 0x55FF55 : 0xA0A0A0);
                ProgressBar.draw(g, left + 2, top + 13, width - 6, summary.done(), summary.total());
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ChecklistScreen.this.mod = mod;
                lastMod = mod;
                scroll = 0;
                list.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(mod);
            }
        }
    }
}
