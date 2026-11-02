package dev.remold.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Vanilla-looking panels and slots drawn with plain fills, so Remold ships no GUI textures. */
public final class UiDraw {
    public static final ResourceLocation ARROW = ResourceLocation.withDefaultNamespace("container/furnace/burn_progress");
    public static final ResourceLocation FLAME = ResourceLocation.withDefaultNamespace("container/furnace/lit_progress");

    private UiDraw() {}

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
        g.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    /** A slot background whose outer top-left corner is (x, y). */
    public static void slot(GuiGraphics g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0xFF8B8B8B);
        g.fill(x, y, x + size - 1, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + size - 1, 0xFF373737);
        g.fill(x + 1, y + size - 1, x + size, y + size, 0xFFFFFFFF);
        g.fill(x + size - 1, y + 1, x + size, y + size, 0xFFFFFFFF);
    }

    public static void arrow(GuiGraphics g, int x, int y) {
        g.blitSprite(ARROW, x, y, 24, 16);
    }

    public static void flame(GuiGraphics g, int x, int y) {
        g.blitSprite(FLAME, x, y, 14, 14);
    }

    /** A small padlock, 9×10, drawn with fills. Red when locked, green when unlocked. */
    public static void padlock(GuiGraphics g, int x, int y, boolean locked) {
        int body = locked ? 0xFFD03030 : 0xFF30B030;
        int dark = 0xFF202020;
        // shackle
        g.fill(x + 2, y, x + 7, y + 1, dark);
        g.fill(x + 1, y + 1, x + 2, y + 5, dark);
        // an open lock has its right arm lifted out of the body
        g.fill(x + 7, y + 1, x + 8, locked ? y + 5 : y + 2, dark);
        // body
        g.fill(x, y + 4, x + 9, y + 10, dark);
        g.fill(x + 1, y + 5, x + 8, y + 9, body);
        g.fill(x + 4, y + 6, x + 5, y + 8, dark);
    }

    public static void highlight(GuiGraphics g, int x, int y, int size) {
        g.fillGradient(net.minecraft.client.renderer.RenderType.guiOverlay(), x, y, x + size, y + size, 0x80FFFFFF, 0x80FFFFFF, 0);
    }
}
