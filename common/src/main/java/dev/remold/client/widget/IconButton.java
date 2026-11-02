package dev.remold.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

public class IconButton extends Button {
    private final Supplier<ItemStack> icon;
    private final boolean iconOnly;

    public IconButton(int x, int y, int w, int h, Component text, Supplier<ItemStack> icon, boolean iconOnly, OnPress onPress) {
        super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.iconOnly = iconOnly;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (iconOnly) {
            Component saved = getMessage();
            setMessage(Component.empty());
            super.renderWidget(g, mouseX, mouseY, partialTick);
            setMessage(saved);
        } else {
            super.renderWidget(g, mouseX, mouseY, partialTick);
        }
        ItemStack stack = icon.get();
        if (stack.isEmpty()) return;
        if (iconOnly && (width < 18 || height < 18)) {
            float scale = Math.min(width, height) / 18f;
            g.pose().pushPose();
            g.pose().translate(getX() + (width - 16 * scale) / 2f, getY() + (height - 16 * scale) / 2f, 0);
            g.pose().scale(scale, scale, 1);
            g.renderItem(stack, 0, 0);
            g.pose().popPose();
        } else if (iconOnly) {
            g.renderItem(stack, getX() + (width - 16) / 2, getY() + (height - 16) / 2);
        } else {
            g.renderItem(stack, getX() + 2, getY() + (height - 16) / 2);
        }
    }

    @Override
    public void renderString(GuiGraphics g, net.minecraft.client.gui.Font font, int color) {
        renderScrollingString(g, font, getMessage(), getX() + 20, getY(), getX() + getWidth() - 2, getY() + getHeight(), color);
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
