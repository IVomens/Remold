package dev.remold.client.screen;

import net.minecraft.client.gui.GuiGraphics;

final class ProgressBar {
    private ProgressBar() {}

    static void draw(GuiGraphics g, int x, int y, int width, int done, int total) {
        g.fill(x, y, x + width, y + 6, 0xFF202020);
        g.fill(x + 1, y + 1, x + width - 1, y + 5, 0xFF404040);
        if (total > 0 && done > 0) {
            int filled = Math.max(1, (int) ((width - 2) * (done / (double) total)));
            g.fill(x + 1, y + 1, x + 1 + filled, y + 5, done >= total ? 0xFF55FF55 : 0xFF3FA9F5);
        }
    }

    static void tick(GuiGraphics g, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        g.fill(x + 9, y + 9, x + 18, y + 18, 0xFF1B5E20);
        g.fill(x + 10, y + 13, x + 12, y + 15, 0xFF8BF58B);
        g.fill(x + 12, y + 15, x + 14, y + 17, 0xFF8BF58B);
        g.fill(x + 14, y + 11, x + 16, y + 15, 0xFF8BF58B);
        g.fill(x + 15, y + 10, x + 17, y + 12, 0xFF8BF58B);
        g.pose().popPose();
    }

    static void dim(GuiGraphics g, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        g.fill(x + 1, y + 1, x + 19, y + 19, 0xB0101010);
        g.pose().popPose();
    }
}
