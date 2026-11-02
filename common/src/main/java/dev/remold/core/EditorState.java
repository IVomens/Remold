package dev.remold.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class EditorState {
    public Machine machine = Machines.SHAPED;
    public GhostIngredient[] inputs = empty(9);
    public ItemStack[] outputs = {ItemStack.EMPTY};
    public String recipeId = "";

    public boolean autoId = true;
    public String group = "";
    public int cookTime = 200;
    public float experience = 0.1f;

    public int exampleIndex;
    @Nullable public TemplateRecipe template;
    public String[] params = new String[0];
    @Nullable public String templateError;

    @Nullable public ResourceLocation loadedFrom;

    private String clean = "";

    public String fingerprint() {
        StringBuilder sb = new StringBuilder(machine.id().toString()).append('|').append(machine.kind());
        for (GhostIngredient g : inputs) {
            sb.append('|').append(g.isEmpty() ? "-" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(g.stack().getItem()));
            if (g.tag() != null) sb.append('#').append(g.tag().location());
        }
        for (ItemStack s : outputs) {
            sb.append("|>").append(s.isEmpty() ? "-" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()) + "x" + s.getCount() + "@" + s.getComponentsPatch().hashCode());
        }
        sb.append('|').append(recipeId).append('|').append(cookTime).append('|').append(experience).append('|').append(exampleIndex);
        for (String p : params) sb.append('|').append(p);
        return sb.toString();
    }

    public void markClean() {
        clean = fingerprint();
    }

    public boolean isDirty() {
        boolean hasContent = java.util.Arrays.stream(inputs).anyMatch(g -> !g.isEmpty())
                || java.util.Arrays.stream(outputs).anyMatch(s -> !s.isEmpty());
        return hasContent && !fingerprint().equals(clean);
    }

    public static GhostIngredient[] empty(int size) {
        GhostIngredient[] array = new GhostIngredient[size];
        Arrays.fill(array, GhostIngredient.EMPTY);
        return array;
    }

    public ItemStack output() {
        return outputs.length == 0 ? ItemStack.EMPTY : outputs[0];
    }

    public void clear() {
        inputs = empty(inputs.length);
        outputs = new ItemStack[outputs.length];
        Arrays.fill(outputs, ItemStack.EMPTY);
        recipeId = "";
        autoId = true;
        group = "";
        loadedFrom = null;
        cookTime = machine.kind().defaultCookTime();
        experience = 0.1f;
    }
}
