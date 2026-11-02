package dev.remold.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remold.Remold;
import dev.remold.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class PickerMemory {
    private static final int MAX_RECENT = 36;
    private static LinkedHashSet<String> favourites;
    private static List<String> recent;

    private PickerMemory() {}

    private static Path file() {
        return Services.PLATFORM.configDir().resolve(Remold.MOD_ID).resolve("picker.json");
    }

    private static void load() {
        if (favourites != null) return;
        favourites = new LinkedHashSet<>();
        recent = new ArrayList<>();
        try {
            if (Files.isRegularFile(file())) {
                JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("favourites")) json.getAsJsonArray("favourites").forEach(e -> favourites.add(e.getAsString()));
                if (json.has("recent")) json.getAsJsonArray("recent").forEach(e -> recent.add(e.getAsString()));
            }
        } catch (Exception e) {
            Remold.LOG.warn("Could not read {}", file(), e);
        }
    }

    private static void save() {
        try {
            JsonObject json = new JsonObject();
            JsonArray fav = new JsonArray();
            favourites.forEach(fav::add);
            JsonArray rec = new JsonArray();
            recent.forEach(rec::add);
            json.add("favourites", fav);
            json.add("recent", rec);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Remold.LOG.warn("Could not write {}", file(), e);
        }
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static boolean isFavourite(Item item) {
        load();
        return favourites.contains(id(item));
    }

    public static void toggleFavourite(Item item) {
        load();
        if (!favourites.remove(id(item))) favourites.add(id(item));
        save();
    }

    public static void used(Item item) {
        load();
        recent.remove(id(item));
        recent.addFirst(id(item));
        while (recent.size() > MAX_RECENT) recent.removeLast();
        save();
    }

    public static List<String> favourites() {
        load();
        return List.copyOf(favourites);
    }

    public static List<String> recent() {
        load();
        return List.copyOf(recent);
    }
}
