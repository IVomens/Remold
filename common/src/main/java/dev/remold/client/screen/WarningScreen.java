package dev.remold.client.screen;

import dev.remold.RemoldText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** "This change may break things" with the details, and Continue anyway / Cancel. */
public class WarningScreen extends Screen {
    private final Screen parent;
    private final List<Component> lines;
    private final Runnable proceed;

    public WarningScreen(Screen parent, List<Component> lines, Runnable proceed) {
        super(RemoldText.tr("check.title"));
        this.parent = parent;
        this.lines = lines;
        this.proceed = proceed;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(RemoldText.tr("check.continue").withStyle(ChatFormatting.YELLOW), b -> {
            minecraft.setScreen(parent);
            proceed.run();
        }).bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.YELLOW), width / 2, 12, 0xFFFFFF);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) wrapped.addAll(font.split(line, Math.min(360, width - 20)));
        int maxLines = (height - 70) / 10;
        int x = width / 2 - Math.min(180, (width - 20) / 2);
        for (int i = 0; i < Math.min(maxLines, wrapped.size()); i++) {
            g.drawString(font, wrapped.get(i), x, 32 + i * 10, 0xE0E0E0);
        }
        g.drawCenteredString(font, RemoldText.tr("check.hint").withStyle(ChatFormatting.DARK_GRAY), width / 2, height - 40, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
