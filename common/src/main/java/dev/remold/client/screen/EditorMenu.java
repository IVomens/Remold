package dev.remold.client.screen;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A client-only menu. It only exists so the editor is a normal container screen: that way JEI,
 * EMI and REI show their item panels next to it automatically, and the player's inventory can be
 * shown. Clicks never reach the server; inventory slots just copy their item as a "ghost".
 */
public class EditorMenu extends AbstractContainerMenu {
    /**
     * Never registered (the menu only exists on the client, and registering it would stop vanilla clients
     * from joining). It just gives recipe viewers that look handlers up by menu type, like EMI, a key.
     */
    @SuppressWarnings("unchecked")
    public static final MenuType<EditorMenu> TYPE = (MenuType<EditorMenu>) dev.remold.platform.Services.PLATFORM.unregisteredMenuType();

    @Nullable public EditorScreen screen;

    public EditorMenu(Inventory inventory, int left, int top) {
        super(TYPE, -42);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, left + col * 18, top + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, left + col * 18, top + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
