package dev.remold.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remold.Remold;
import dev.remold.platform.Services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Progress {
    private static Set<String> done;
    private static Set<String> skipped;

    private Progress() {}

    private static Path file() {
        return Services.PLATFORM.configDir().resolve(Remold.MOD_ID).resolve("progress.json");
    }

    private static void load() {
        if (done != null) return;
        done = new LinkedHashSet<>();
        skipped = new LinkedHashSet<>();
        try {
            if (Files.isRegularFile(file())) {
                JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("done")) json.getAsJsonArray("done").forEach(e -> done.add(e.getAsString()));
                if (json.has("skipped")) json.getAsJsonArray("skipped").forEach(e -> skipped.add(e.getAsString()));
            }
        } catch (Exception e) {
            Remold.LOG.warn("Could not read {}", file(), e);
        }
    }

    private static void save() {
        try {
            JsonObject json = new JsonObject();
            JsonArray d = new JsonArray();
            done.forEach(d::add);
            JsonArray s = new JsonArray();
            skipped.forEach(s::add);
            json.add("done", d);
            json.add("skipped", s);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Remold.LOG.warn("Could not write {}", file(), e);
        }
    }

    public static String fileKey(String source, String recipe) {
        return "file:" + source + "#" + recipe;
    }

    public static String itemKey(String item) {
        return "item:" + item;
    }

    public static boolean isDone(String key) {
        load();
        return done.contains(key);
    }

    public static boolean isSkipped(String key) {
        load();
        return skipped.contains(key);
    }

    public static void setDone(String key, boolean value) {
        load();
        boolean changed = value ? done.add(key) : done.remove(key);
        if (value) changed |= skipped.remove(key);
        if (changed) save();
    }

    public static void setSkipped(String key, boolean value) {
        load();
        boolean changed = value ? skipped.add(key) : skipped.remove(key);
        if (value) changed |= done.remove(key);
        if (changed) save();
    }
}
