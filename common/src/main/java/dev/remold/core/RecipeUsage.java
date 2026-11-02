package dev.remold.core;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Set;

public final class RecipeUsage {
    private RecipeUsage() {}

    public static boolean uses(Recipe<?> recipe, ItemStack stack) {
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (!ingredient.isEmpty() && ingredient.test(stack)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean usesAny(Recipe<?> recipe, Set<Item> items) {
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                for (ItemStack stack : ingredient.getItems()) {
                    if (items.contains(stack.getItem())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean produces(Recipe<?> recipe, Set<Item> items, net.minecraft.core.HolderLookup.Provider registries) {
        try {
            ItemStack result = recipe.getResultItem(registries);
            return !result.isEmpty() && items.contains(result.getItem());
        } catch (Exception e) {
            return false;
        }
    }
}
