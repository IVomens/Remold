package dev.remold.client.screen;

import dev.remold.core.ProgressionCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.Set;

/** Warning lines for items that would stop being craftable if these recipes were removed. */
final class RemovalCheck {
    private RemovalCheck() {}

    static List<Component> lost(Minecraft mc, Set<ResourceLocation> removed, Set<Item> intended) {
        var all = mc.level.getRecipeManager().getRecipes();
        List<Recipe<?>> before = all.stream().<Recipe<?>>map(RecipeHolder::value).toList();
        List<Item> lost = ProgressionCheck.lostItems(before, ProgressionCheck.without(all, removed), intended, mc.level.registryAccess());
        return ProgressionCheck.describeLost(lost, 12);
    }
}
