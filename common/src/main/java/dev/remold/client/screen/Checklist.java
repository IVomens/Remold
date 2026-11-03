package dev.remold.client.screen;

import dev.remold.client.ClientState;
import dev.remold.client.Progress;
import dev.remold.data.ChangeEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class Checklist {
    public enum Status { TODO, DONE, SKIPPED }

    public record Summary(int done, int total) {}

    private static List<ChangeEntry> cachedFor;
    private static Map<Item, RecipeHolder<?>> made = Map.of();

    private Checklist() {}

    public static List<String> mods() {
        TreeSet<String> mods = new TreeSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) mods.add(BuiltInRegistries.ITEM.getKey(item).getNamespace());
        }
        List<String> list = new ArrayList<>(mods);
        list.sort(Comparator.comparing((String m) -> m.equals("minecraft")).thenComparing(m -> dev.remold.platform.Services.PLATFORM.modName(m).toLowerCase()));
        return list;
    }

    public static List<Item> items(String mod) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (item != Items.AIR && id.getNamespace().equals(mod)) items.add(item);
        }
        items.sort(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).getPath()));
        return items;
    }

    private static Map<Item, RecipeHolder<?>> made() {
        if (cachedFor != ClientState.changes()) {
            cachedFor = ClientState.changes();
            Map<Item, RecipeHolder<?>> map = new HashMap<>();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                for (ChangeEntry change : ClientState.changes()) {
                    if (change.kind() == ChangeEntry.Kind.REMOVED) continue;
                    mc.level.getRecipeManager().byKey(change.id()).ifPresent(h -> {
                        try {
                            ItemStack out = h.value().getResultItem(mc.level.registryAccess());
                            if (!out.isEmpty()) map.putIfAbsent(out.getItem(), h);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            made = map;
        }
        return made;
    }

    @Nullable
    public static RecipeHolder<?> remoldRecipeFor(Item item) {
        return made().get(item);
    }

    public static Status status(Item item) {
        String key = Progress.itemKey(BuiltInRegistries.ITEM.getKey(item).toString());
        if (Progress.isSkipped(key)) return Status.SKIPPED;
        if (Progress.isDone(key) || made().containsKey(item)) return Status.DONE;
        return Status.TODO;
    }

    public static Summary summary(String mod) {
        int done = 0, total = 0;
        for (Item item : items(mod)) {
            Status s = status(item);
            if (s == Status.SKIPPED) continue;
            total++;
            if (s == Status.DONE) done++;
        }
        return new Summary(done, total);
    }

    @Nullable
    public static Item nextToDo(String mod, @Nullable Item after) {
        List<Item> items = items(mod);
        int start = after == null ? 0 : items.indexOf(after) + 1;
        for (int k = 0; k < items.size(); k++) {
            Item item = items.get((start + k) % items.size());
            if (item != after && status(item) == Status.TODO) return item;
        }
        return null;
    }
}
