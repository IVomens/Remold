package dev.remold.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ViewerBridge {
    private static final List<Function<ResourceLocation, ItemStack>> CATALYSTS = new ArrayList<>();
    private static final List<Runnable> ANNOTATION_LISTENERS = new ArrayList<>();

    private ViewerBridge() {}

    public static void addCatalystLookup(Function<ResourceLocation, ItemStack> lookup) {
        CATALYSTS.add(lookup);
    }

    public static ItemStack catalyst(ResourceLocation recipeType) {
        for (var lookup : CATALYSTS) {
            try {
                ItemStack stack = lookup.apply(recipeType);
                if (stack != null && !stack.isEmpty()) return stack;
            } catch (Exception ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    public static void onAnnotationsChanged(Runnable listener) {
        ANNOTATION_LISTENERS.add(listener);
    }

    public static void fireAnnotationsChanged() {
        ANNOTATION_LISTENERS.forEach(Runnable::run);
    }
}
