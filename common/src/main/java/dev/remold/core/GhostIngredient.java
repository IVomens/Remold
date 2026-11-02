package dev.remold.core;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An input slot in the editor: either a single item or an item tag (e.g. {@code #minecraft:planks}).
 * Nothing is consumed from the player; it is a "ghost" of an item.
 */
public record GhostIngredient(ItemStack stack, @Nullable TagKey<Item> tag) {
    public static final GhostIngredient EMPTY = new GhostIngredient(ItemStack.EMPTY, null);

    public static GhostIngredient of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new GhostIngredient(stack.copyWithCount(1), null);
    }

    public boolean isEmpty() {
        return stack.isEmpty() && tag == null;
    }

    public Ingredient toIngredient() {
        if (tag != null) return Ingredient.of(tag);
        return isEmpty() ? Ingredient.EMPTY : Ingredient.of(stack.getItem());
    }

    /** The stack to draw. Tags cycle through their members once a second. */
    public ItemStack display() {
        if (tag == null) return stack;
        List<Holder<Item>> items = tagItems(tag);
        if (items.isEmpty()) return stack;
        int index = (int) ((System.currentTimeMillis() / 1000) % items.size());
        return new ItemStack(items.get(index));
    }

    /** Next tag of this item (or back to the plain item), used for "middle click to cycle tags". */
    public GhostIngredient cycleTag() {
        if (stack.isEmpty()) return this;
        List<TagKey<Item>> tags = stack.getTags().sorted((a, b) -> a.location().compareTo(b.location())).toList();
        if (tags.isEmpty()) return this;
        int index = tag == null ? 0 : tags.indexOf(tag) + 1;
        return new GhostIngredient(stack, index >= tags.size() ? null : tags.get(index));
    }

    public static List<Holder<Item>> tagItems(TagKey<Item> tag) {
        return BuiltInRegistries.ITEM.getTag(tag).map(set -> set.stream().toList()).orElse(List.of());
    }

    /**
     * Converts a real {@link Ingredient} back into a ghost. Tags are detected by encoding the
     * ingredient, which works for vanilla and loader ingredient types alike.
     */
    public static GhostIngredient fromIngredient(Ingredient ingredient, HolderLookup.Provider registries) {
        if (ingredient.isEmpty()) return EMPTY;
        ItemStack[] items = ingredient.getItems();
        ItemStack first = items.length > 0 ? items[0].copyWithCount(1) : ItemStack.EMPTY;
        try {
            JsonElement json = Ingredient.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), ingredient).getOrThrow();
            if (json.isJsonObject() && json.getAsJsonObject().has("tag")) {
                ResourceLocation tagId = ResourceLocation.parse(json.getAsJsonObject().get("tag").getAsString());
                TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
                if (first.isEmpty()) {
                    first = tagItems(tag).stream().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
                }
                return new GhostIngredient(first, tag);
            }
        } catch (Exception ignored) {
            // Custom ingredient types that cannot be encoded: fall back to the first matching item.
        }
        return of(first);
    }
}
