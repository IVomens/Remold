package dev.remold.client.screen;

import com.google.gson.JsonParser;
import dev.remold.RemoldText;
import dev.remold.client.Progress;
import dev.remold.client.widget.UiDraw;
import dev.remold.network.Payloads;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public class FilesScreen extends Screen {
    enum Filter { ALL, TODO, DONE }

    private static String lastKey;

    private final Screen parent;
    private final EditorScreen editor;
    private List<Payloads.SourceInfo> files = List.of();
    private final Map<String, List<Payloads.SourceEntry>> entries = new HashMap<>();
    @Nullable private String key = lastKey;
    private Filter filter = Filter.ALL;
    private List<Payloads.SourceEntry> shown = List.of();
    private boolean loaded;
    private int scroll;
    private FileList list;

    public FilesScreen(Screen parent, EditorScreen editor) {
        super(RemoldText.tr("files.title"));
        this.parent = parent;
        this.editor = editor;
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
        list = new FileList(minecraft, width / 2 - 30, height - 72, 34, 24);
        list.setX(5);
        list.set();
        addRenderableWidget(list);
        addRenderableWidget(CycleButton.<Filter>builder(f -> RemoldText.tr("checklist.filter." + f.name().toLowerCase(Locale.ROOT)))
                .withValues(Filter.values()).withInitialValue(filter)
                .create(width - 110, 30, 100, 20, RemoldText.tr("checklist.show"), (b, v) -> {
                    filter = v;
                    scroll = 0;
                })).setTooltip(Tooltip.create(RemoldText.tr("files.show.hint")));
        addRenderableWidget(Button.builder(RemoldText.tr("import.refresh"), b -> request()).bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
        request();
    }

    private void request() {
        Services.PLATFORM.sendToServer(new Payloads.SourceRequest("", -1));
        if (key != null) Services.PLATFORM.sendToServer(new Payloads.SourceRequest(key, -1));
    }

    public void receive(Payloads.SourceList payload) {
        files = payload.files();
        loaded = true;
        if (key != null && files.stream().noneMatch(f -> f.key().equals(key))) key = null;
        if (key == null && !files.isEmpty()) select(files.getFirst().key());
        list.set();
    }

    public void receive(Payloads.SourceEntries payload) {
        entries.put(payload.key(), payload.entries());
    }

    public void receive(Payloads.SourceRecipe payload) {
        Payloads.SourceInfo info = files.stream().filter(f -> f.key().equals(payload.key())).findFirst().orElse(null);
        String name = info == null ? payload.key() : info.name();
        editor.returnTo(this);
        editor.loadFromSource(payload.key(), payload.index(), name, ResourceLocation.parse(payload.id()),
                JsonParser.parseString(payload.json()).getAsJsonObject());
        minecraft.setScreen(editor);
    }

    private void select(String newKey) {
        key = newKey;
        lastKey = newKey;
        scroll = 0;
        if (!entries.containsKey(newKey)) Services.PLATFORM.sendToServer(new Payloads.SourceRequest(newKey, -1));
    }

    private boolean done(Payloads.SourceEntry e) {
        return Progress.isDone(Progress.fileKey(key, e.id()));
    }

    private int doneCount(String fileKey) {
        int n = 0;
        for (Payloads.SourceEntry e : entries.getOrDefault(fileKey, List.of())) {
            if (Progress.isDone(Progress.fileKey(fileKey, e.id()))) n++;
        }
        return n;
    }

    private void computeShown() {
        List<Payloads.SourceEntry> list = new ArrayList<>();
        for (Payloads.SourceEntry e : entries.getOrDefault(key, List.of())) {
            boolean keep = switch (filter) {
                case ALL -> true;
                case TODO -> !done(e);
                case DONE -> done(e);
            };
            if (keep) list.add(e);
        }
        shown = list;
    }

    private static ItemStack icon(Payloads.SourceEntry e) {
        if (!e.problem().isEmpty()) return new ItemStack(Items.BARRIER);
        ResourceLocation id = ResourceLocation.tryParse(e.output());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return new ItemStack(Items.PAPER);
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private int entryAt(double mouseX, double mouseY) {
        if (mouseX < gridX() || mouseY < gridY()) return -1;
        int c = (int) ((mouseX - gridX()) / 20), r = (int) ((mouseY - gridY()) / 20);
        if (c >= cols() || r >= rows()) return -1;
        int i = (scroll + r) * cols() + c;
        return i < shown.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = entryAt(mouseX, mouseY);
        if (i >= 0 && key != null) {
            Payloads.SourceEntry e = shown.get(i);
            if (button == 1) {
                Progress.setDone(Progress.fileKey(key, e.id()), !done(e));
            } else if (button == 0 && e.problem().isEmpty()) {
                Services.PLATFORM.sendToServer(new Payloads.SourceRequest(key, e.index()));
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
        g.drawCenteredString(font, RemoldText.tr("files.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        if (loaded && files.isEmpty()) {
            int y = 60;
            for (var line : font.split(RemoldText.tr("files.none").withStyle(ChatFormatting.GRAY), Math.min(360, width - 40))) {
                g.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
                y += 10;
            }
            return;
        }
        if (key == null) return;
        computeShown();
        List<Payloads.SourceEntry> all = entries.getOrDefault(key, List.of());
        Payloads.SourceInfo info = files.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
        g.drawString(font, font.plainSubstrByWidth(info == null ? key : info.name(), width - gridX() - 120), gridX(), 32, 0xFFFF55);
        int done = doneCount(key);
        g.drawString(font, RemoldText.tr("checklist.progress", done, all.size()), gridX(), 42, 0xC0C0C0);
        ProgressBar.draw(g, gridX() + 90, 43, Math.max(20, width - gridX() - 210), done, all.size());
        int cols = cols(), rows = rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scroll + r) * cols + c;
                if (i >= shown.size()) break;
                int x = gridX() + c * 20, y = gridY() + r * 20;
                Payloads.SourceEntry e = shown.get(i);
                UiDraw.slot(g, x, y, 20);
                g.renderItem(icon(e), x + 2, y + 2);
                if (!e.problem().isEmpty()) ProgressBar.dim(g, x, y);
                else if (done(e)) ProgressBar.tick(g, x, y);
            }
        }
        if (!entries.containsKey(key)) g.drawString(font, RemoldText.tr("import.loading"), gridX(), gridY() + 4, 0x808080);
        else if (shown.isEmpty()) g.drawString(font, RemoldText.tr("checklist.empty"), gridX(), gridY() + 4, 0x808080);
        var legend = font.split(RemoldText.tr("files.legend").withStyle(ChatFormatting.DARK_GRAY), width - gridX() - 8);
        for (int i = 0; i < legend.size(); i++) g.drawString(font, legend.get(i), gridX(), height - 40 - (legend.size() - 1 - i) * 10, 0xFFFFFF);
        int hovered = entryAt(mouseX, mouseY);
        if (hovered >= 0) {
            Payloads.SourceEntry e = shown.get(hovered);
            List<Component> lines = new ArrayList<>();
            ItemStack icon = icon(e);
            lines.add(e.problem().isEmpty() && !icon.is(Items.PAPER) ? icon.getHoverName() : Component.literal(e.output().isEmpty() ? e.id() : e.output()));
            lines.add(Component.literal(e.id()).withStyle(ChatFormatting.DARK_GRAY));
            if (!e.problem().isEmpty()) {
                lines.add(RemoldText.tr("files.problem." + e.problem()).withStyle(ChatFormatting.RED));
            } else {
                lines.add(RemoldText.tr(done(e) ? "checklist.status.done" : "checklist.status.todo").withStyle(done(e) ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                lines.add(RemoldText.tr("files.click").withStyle(ChatFormatting.YELLOW));
            }
            lines.add(RemoldText.tr("files.click.other").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class FileList extends ObjectSelectionList<FileList.Entry> {
        FileList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set() {
            replaceEntries(files.stream().map(Entry::new).toList());
            children().stream().filter(e -> e.info.key().equals(key)).findFirst().ifPresent(this::setSelected);
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
            final Payloads.SourceInfo info;

            Entry(Payloads.SourceInfo info) {
                this.info = info;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                ItemStack icon = new ItemStack(switch (info.kind()) {
                    case "kubejs" -> Items.WRITABLE_BOOK;
                    case "zip" -> Items.BARREL;
                    default -> Items.CHEST;
                });
                g.renderItem(icon, left + 1, top + 2);
                g.drawString(font, font.plainSubstrByWidth(info.name(), width - 24), left + 20, top + 2, 0xFFFFFF);
                int done = doneCount(info.key());
                g.drawString(font, RemoldText.tr("files.count." + info.kind(), info.recipes(), done), left + 20, top + 12, 0x909090);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                select(info.key());
                list.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(info.name());
            }
        }
    }
}
