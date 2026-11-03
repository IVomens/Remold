package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class ImportScreen extends Screen {
    private final Screen parent;
    private List<Payloads.ImportSummary> files = List.of();
    private String folder = "";
    private boolean loaded;
    @Nullable private Payloads.ImportSummary selected;
    private FileList list;
    private Button importButton;

    public ImportScreen(Screen parent) {
        super(RemoldText.tr("import.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new FileList(minecraft, width, height - 110, 30, 24);
        addRenderableWidget(list);
        int y = height - 30, x = width / 2 - 160;
        importButton = addRenderableWidget(Button.builder(RemoldText.tr("import.run").withStyle(ChatFormatting.GREEN), b -> {
            if (selected == null) return;
            Services.PLATFORM.sendToServer(new Payloads.ImportRequest(selected.file(), !hasShiftDown()));
        }).bounds(x, y, 78, 20).build());
        importButton.setTooltip(Tooltip.create(RemoldText.tr("import.run.hint")));
        addRenderableWidget(Button.builder(RemoldText.tr("import.refresh"), b -> request()).bounds(x + 81, y, 78, 20).build());
        Button open = addRenderableWidget(Button.builder(RemoldText.tr("import.open"), b -> Util.getPlatform().openFile(new File(folder)))
                .bounds(x + 162, y, 78, 20).build());

        open.active = minecraft.hasSingleplayerServer() && !folder.isEmpty();
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x + 243, y, 78, 20).build());
        list.set(files);
        updateButtons();
        if (!loaded) request();
    }

    private void request() {
        Services.PLATFORM.sendToServer(new Payloads.ImportRequest("", false));
    }

    public void receive(Payloads.ImportList payload) {
        files = payload.files();
        folder = payload.folder();
        loaded = true;
        String keep = selected == null ? null : selected.file();
        selected = files.stream().filter(f -> f.file().equals(keep)).findFirst().orElse(null);
        rebuildWidgets();
    }

    private void updateButtons() {
        importButton.active = selected != null && selected.error().isEmpty();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int y = height - 74;
        Component info = !loaded ? RemoldText.tr("import.loading")
                : files.isEmpty() ? RemoldText.tr("import.empty", folder) : RemoldText.tr("import.help", folder);
        for (FormattedCharSequence line : font.split(info.copy().withStyle(ChatFormatting.GRAY), Math.min(420, width - 20))) {
            g.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
            y += 10;
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

        void set(List<Payloads.ImportSummary> summaries) {
            replaceEntries(summaries.stream().map(Entry::new).toList());
            children().stream().filter(e -> e.summary == selected).findFirst().ifPresent(this::setSelected);
        }

        @Override
        public int getRowWidth() {
            return 320;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final Payloads.ImportSummary summary;

            Entry(Payloads.ImportSummary summary) {
                this.summary = summary;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                g.drawString(font, font.plainSubstrByWidth(summary.file(), width - 4), left + 2, top + 2, 0xFFFFFF);
                Component sub = summary.error().isEmpty()
                        ? RemoldText.tr("import.summary", summary.recipes(), summary.removals(), summary.tags(), summary.annotations())
                        : RemoldText.tr("import.bad", summary.error()).withStyle(ChatFormatting.RED);
                g.drawString(font, sub, left + 2, top + 12, 0x909090);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                selected = summary;
                list.setSelected(this);
                updateButtons();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(summary.file());
            }
        }
    }
}
