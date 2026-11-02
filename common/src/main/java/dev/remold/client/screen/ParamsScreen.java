package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.core.EditorState;
import dev.remold.core.TemplateRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ParamsScreen extends Screen {
    private final EditorScreen editor;
    private int page;

    public ParamsScreen(EditorScreen editor) {
        super(RemoldText.tr("values.title"));
        this.editor = editor;
    }

    private int perPage() {
        return Math.max(1, (height - 80) / 22);
    }

    @Override
    protected void init() {
        EditorState state = editor.state;
        if (state.template == null) {
            onClose();
            return;
        }
        List<TemplateRecipe.Param> params = state.template.params;
        int pages = (params.size() + perPage() - 1) / perPage();
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * perPage();
        for (int i = start; i < Math.min(params.size(), start + perPage()); i++) {
            int index = i;
            EditBox box = new EditBox(font, width / 2 + 10, 34 + (i - start) * 22, 140, 16, Component.literal(params.get(i).label()));
            box.setMaxLength(512);
            box.setValue(state.params[i]);
            box.setResponder(s -> state.params[index] = s);
            addRenderableWidget(box);
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(width / 2 - 160, height - 28, 20, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(width / 2 - 136, height - 28, 20, 20).build()).active = page < pages - 1;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, RemoldText.tr("values.hint").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        EditorState state = editor.state;
        if (state.template == null) return;
        List<TemplateRecipe.Param> params = state.template.params;
        int start = page * perPage();
        for (int i = start; i < Math.min(params.size(), start + perPage()); i++) {
            String label = font.plainSubstrByWidth(params.get(i).label(), width / 2 - 20);
            g.drawString(font, label, width / 2 - 4 - font.width(label), 38 + (i - start) * 22, 0xE0E0E0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(editor);
    }
}
