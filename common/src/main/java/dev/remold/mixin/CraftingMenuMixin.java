package dev.remold.mixin;

import dev.remold.server.Gates;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recipe gating: after the crafting grid (crafting table or inventory) works out its result, clear
 * the result if the recipe is locked for this player. Runs on the server only.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void remold$gate(AbstractContainerMenu menu, Level level, Player player, CraftingContainer craftSlots,
                                    ResultContainer resultSlots, @Nullable RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && Gates.isLocked(serverPlayer, resultSlots.getRecipeUsed())) {
            resultSlots.setItem(0, ItemStack.EMPTY);
            menu.setRemoteSlot(0, ItemStack.EMPTY);
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, ItemStack.EMPTY));
        }
    }
}
