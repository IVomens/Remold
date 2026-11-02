package dev.remold.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import dev.remold.data.TagEdit;
import dev.remold.network.Payloads;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Importer {
    public static final String FOLDER = "remold-imports";
    private static final long MAX_ENTRY = 4L << 20;
    private static final long MAX_TOTAL = 64L << 20;
    private static final Set<String> CONDITION_KEYS = Set.of("neoforge:conditions", "fabric:load_conditions", "conditions");
    private static final Codec<Map<ResourceLocation, TagEdit>> TAGS_CODEC = Codec.unboundedMap(ResourceLocation.CODEC, TagEdit.CODEC);
    private static final Codec<Map<ResourceLocation, Annotation>> ANNOTATIONS_CODEC = Codec.unboundedMap(ResourceLocation.CODEC, Annotation.CODEC);

    private Importer() {}

    record Contents(Map<ResourceLocation, JsonObject> recipes, Set<ResourceLocation> removals,
                    Map<ResourceLocation, TagEdit> tags, Map<ResourceLocation, Annotation> annotations) {}

    public static Path folder(MinecraftServer server) {
        return server.getServerDirectory().resolve(FOLDER);
    }

    public static List<Payloads.ImportSummary> list(MinecraftServer server) {
        List<Payloads.ImportSummary> result = new ArrayList<>();
        Path dir = folder(server);
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    try {
                        Contents c = read(file);
                        result.add(new Payloads.ImportSummary(name, c.recipes().size(), c.removals().size(), c.tags().size(), c.annotations().size(), ""));
                    } catch (Exception e) {
                        result.add(new Payloads.ImportSummary(name, 0, 0, 0, 0, String.valueOf(e.getMessage())));
                    }
                }
            }
        } catch (IOException e) {
            Remold.LOG.error("Could not list {}", dir, e);
        }
        return result;
    }

    private static Path resolve(MinecraftServer server, String name) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || !name.endsWith(".zip")) {
            throw new IOException("Invalid file name " + name);
        }
        Path file = folder(server).resolve(name);
        if (!Files.isRegularFile(file)) throw new IOException("No such file " + name);
        return file;
    }

    static Contents read(Path zip) throws IOException {
        Map<ResourceLocation, JsonObject> recipes = new LinkedHashMap<>();
        Set<ResourceLocation> removals = new LinkedHashSet<>();
        Map<ResourceLocation, TagEdit> tagFiles = new LinkedHashMap<>();
        Map<ResourceLocation, TagEdit> tags = null;
        Map<ResourceLocation, Annotation> annotations = new HashMap<>();
        long total = 0;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                byte[] bytes = zis.readNBytes((int) MAX_ENTRY + 1);
                if (bytes.length > MAX_ENTRY) throw new IOException("File too large in pack: " + name);
                total += bytes.length;
                if (total > MAX_TOTAL) throw new IOException("Pack is too large");
                if (!name.endsWith(".json")) continue;
                String text = new String(bytes, StandardCharsets.UTF_8);
                String[] parts = name.split("/");
                if (name.equals("remold_tags.json")) {
                    tags = TAGS_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).getOrThrow(IOException::new);
                } else if (name.equals("remold_annotations.json")) {
                    annotations.putAll(ANNOTATIONS_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).getOrThrow(IOException::new));
                } else if (parts.length >= 4 && parts[0].equals("data") && (parts[2].equals("recipe") || parts[2].equals("recipes"))) {
                    ResourceLocation id = idOf(parts[1], parts, 3);
                    if (id == null) continue;
                    JsonElement json = JsonParser.parseString(text);
                    if (!json.isJsonObject()) continue;
                    JsonObject obj = json.getAsJsonObject();
                    boolean onlyConditions = !obj.keySet().isEmpty() && CONDITION_KEYS.containsAll(obj.keySet());
                    if (onlyConditions) removals.add(id);
                    else recipes.put(id, obj);
                } else if (parts.length >= 5 && parts[0].equals("data") && parts[2].equals("tags")
                        && (parts[3].equals("item") || parts[3].equals("items"))) {
                    ResourceLocation id = idOf(parts[1], parts, 4);
                    if (id == null) continue;
                    tagFiles.put(id, tagFileEdit(JsonParser.parseString(text).getAsJsonObject()));
                }
            }
        }

        return new Contents(recipes, removals, tags != null ? tags : tagFiles, annotations);
    }

    private static ResourceLocation idOf(String namespace, String[] parts, int from) {
        String path = String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
        if (!path.endsWith(".json")) return null;
        return ResourceLocation.tryBuild(namespace, path.substring(0, path.length() - 5));
    }

    private static TagEdit tagFileEdit(JsonObject json) {
        List<String> add = new ArrayList<>(), remove = new ArrayList<>();
        if (json.has("values")) json.getAsJsonArray("values").forEach(e -> add.add(e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString()));
        if (json.has("remove")) json.getAsJsonArray("remove").forEach(e -> remove.add(e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString()));
        return new TagEdit(add, remove);
    }

    public static void run(ServerPlayer player, String fileName, boolean apply) {
        MinecraftServer server = player.server;
        Contents contents;
        try {
            contents = read(resolve(server, fileName));
        } catch (Exception e) {
            ServerActions.notice(player, false, "import_failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        RemoldPack pack = RemoldPack.get(server);
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        int recipes = 0, skipped = 0, removed = 0;
        try {
            pack.beginBatch(player.getGameProfile().getName());
            for (var e : contents.recipes().entrySet()) {
                if (Recipe.CODEC.parse(ops, e.getValue()).error().isPresent()) {
                    skipped++;
                    continue;
                }
                ResourceLocation id = e.getKey();
                ChangeEntry.Kind kind = pack.change(id).map(c -> c.kind() == ChangeEntry.Kind.ADDED ? c.kind() : ChangeEntry.Kind.REPLACED)
                        .orElse(server.getRecipeManager().byKey(id).isPresent() ? ChangeEntry.Kind.REPLACED : ChangeEntry.Kind.ADDED);
                String type = e.getValue().has("type") ? e.getValue().get("type").getAsString() : "";
                pack.writeRecipe(id, e.getValue(), ServerActions.entry(player, id, kind, type));
                recipes++;
            }
            for (ResourceLocation id : contents.removals()) {
                if (ServerActions.remove(player, pack, id)) removed++;
            }
            for (var e : contents.tags().entrySet()) {
                TagEdit existing = pack.tagEdits().getOrDefault(e.getKey(), TagEdit.EMPTY);
                LinkedHashSet<String> add = new LinkedHashSet<>(existing.add()), remove = new LinkedHashSet<>(existing.remove());
                add.addAll(e.getValue().add());
                remove.addAll(e.getValue().remove());
                List<String> current = new ArrayList<>();
                server.registryAccess().registryOrThrow(Registries.ITEM).getTag(TagKey.create(Registries.ITEM, e.getKey()))
                        .ifPresent(set -> set.forEach(h -> h.unwrapKey().ifPresent(k -> current.add(k.location().toString()))));
                pack.setTagEdit(e.getKey(), new TagEdit(List.copyOf(add), List.copyOf(remove)), current);
            }
            for (var e : contents.annotations().entrySet()) {
                pack.setAnnotation(e.getKey(), e.getValue());
            }
        } catch (IOException e) {
            Remold.LOG.error("Import of {} failed", fileName, e);
            ServerActions.notice(player, false, "import_failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        if (!contents.annotations().isEmpty()) ServerActions.broadcastAnnotations(server);
        ServerActions.finish(player, apply, skipped > 0 ? "imported_skipped" : "imported", fileName,
                Integer.toString(recipes), Integer.toString(removed), Integer.toString(contents.tags().size()), Integer.toString(skipped));
    }
}
