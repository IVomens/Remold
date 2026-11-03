package dev.remold.client.screen;

import dev.remold.client.widget.UiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

public class ItemSlotWidget extends AbstractWidget {
    private final Screen owner;
    private final Consumer<ItemStack> onChange;
    private ItemStack stack = ItemStack.EMPTY;

    public ItemSlotWidget(Screen owner, int x, int y, Component label, Consumer<ItemStack> onChange) {
        super(x, y, 18, 18, label);
        this.owner = owner;
        this.onChange = onChange;
    }

    public ItemStack stack() {
        return stack;
    }

    public void setStack(ItemStack stack) {
        this.stack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        UiDraw.slot(g, getX(), getY(), 18);
        g.renderItem(stack, getX() + 1, getY() + 1);
        if (isHovered()) {
            UiDraw.highlight(g, getX() + 1, getY() + 1, 16);
            Minecraft mc = Minecraft.getInstance();
            if (!stack.isEmpty()) g.renderTooltip(mc.font, stack, mouseX, mouseY);
            else g.renderTooltip(mc.font, getMessage(), mouseX, mouseY);
        }
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || !isMouseOver(mouseX, mouseY) || !isValidClickButton(button)) return false;
        if (button == 1) {
            setStack(ItemStack.EMPTY);
            onChange.accept(stack);
        } else {
            Minecraft.getInstance().setScreen(new ItemPickerScreen(owner, picked -> {
                setStack(picked);
                onChange.accept(stack);
            }));
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
