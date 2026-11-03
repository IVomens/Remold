package dev.remold.client.screen;

import dev.remold.RemoldText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public class ConflictScreen extends Screen {
    private static final int MAX_SHOWN = 6;

    private final Screen parent;
    private final List<ResourceLocation> conflicts;
    private final Runnable saveAnyway;
    private final Runnable replace;

    public ConflictScreen(Screen parent, List<ResourceLocation> conflicts, Runnable saveAnyway, Runnable replace) {
        super(RemoldText.tr("conflict.title"));
        this.parent = parent;
        this.conflicts = conflicts;
        this.saveAnyway = saveAnyway;
        this.replace = replace;
    }

    @Override
    protected void init() {
        int y = Math.min(height - 28, 70 + Math.min(conflicts.size(), MAX_SHOWN + 1) * 11 + 24);
        Button replaceButton = Button.builder(RemoldText.tr("conflict.replace").withStyle(ChatFormatting.YELLOW), b -> {
            minecraft.setScreen(parent);
            replace.run();
        }).bounds(width / 2 - 154, y, 100, 20).build();
        replaceButton.setTooltip(Tooltip.create(RemoldText.tr("conflict.replace.hint")));
        addRenderableWidget(replaceButton);
        Button anyway = Button.builder(RemoldText.tr("conflict.anyway"), b -> {
            minecraft.setScreen(parent);
            saveAnyway.run();
        }).bounds(width / 2 - 50, y, 100, 20).build();
        anyway.setTooltip(Tooltip.create(RemoldText.tr("conflict.anyway.hint")));
        addRenderableWidget(anyway);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 54, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.YELLOW), width / 2, 20, 0xFFFFFF);
        List<FormattedCharSequence> lines = font.split(RemoldText.tr("conflict.message"), Math.min(320, width - 20));
        int y = 36;
        for (FormattedCharSequence line : lines) {
            g.drawCenteredString(font, line, width / 2, y, 0xE0E0E0);
            y += 10;
        }
        y += 6;
        for (int i = 0; i < Math.min(conflicts.size(), MAX_SHOWN); i++) {
            g.drawCenteredString(font, conflicts.get(i).toString(), width / 2, y, 0xFFFF55);
            y += 11;
        }
        if (conflicts.size() > MAX_SHOWN) {
            g.drawCenteredString(font, RemoldText.tr("editor.more", conflicts.size() - MAX_SHOWN), width / 2, y, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
