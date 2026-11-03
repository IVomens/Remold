package dev.remold.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.ChangeEntry;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SourceFiles {
    private static final String KUBEJS = "kubejs/";
    private static final String PACK = "pack/";
    private static final String ZIP = "zip/";
    private static final Set<String> CONDITION_KEYS = Set.of("neoforge:conditions", "fabric:load_conditions", "conditions");
    private static final long MAX_FILE = 4L << 20;

    private SourceFiles() {}

    private record Recipe0(ResourceLocation id, @Nullable Path file, String json) {}

    private static Path scripts(MinecraftServer server) {
        return server.getServerDirectory().resolve("kubejs").resolve("server_scripts");
    }

    private static Path datapacks(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR);
    }

    public static List<Payloads.SourceInfo> list(MinecraftServer server) {
        List<Payloads.SourceInfo> result = new ArrayList<>();
        Path scripts = scripts(server);
        if (Files.isDirectory(scripts)) {
            try (Stream<Path> files = Files.walk(scripts)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".js") && Files.isRegularFile(f)).sorted().toList()) {
                    String rel = scripts.relativize(file).toString().replace('\\', '/');
                    int count = 0;
                    try {
                        count = KubeJsScript.parse(read(file)).size();
                    } catch (IOException ignored) {
                    }
                    result.add(new Payloads.SourceInfo(KUBEJS + rel, "kubejs", rel, count));
                }
            } catch (IOException e) {
                Remold.LOG.error("Could not list {}", scripts, e);
            }
        }
        Path packs = datapacks(server);
        if (Files.isDirectory(packs)) {
            try (Stream<Path> children = Files.list(packs)) {
                for (Path child : children.sorted().toList()) {
                    String name = child.getFileName().toString();
                    if (name.equals(RemoldPack.FOLDER)) continue;
                    try {
                        if (Files.isDirectory(child)) {
                            result.add(new Payloads.SourceInfo(PACK + name, "datapack", name, packRecipes(child).size()));
                        } else if (name.endsWith(".zip")) {
                            result.add(new Payloads.SourceInfo(ZIP + name, "zip", name, zipRecipes(child).size()));
                        }
                    } catch (IOException e) {
                        Remold.LOG.warn("Could not read datapack {}", child, e);
                    }
                }
            } catch (IOException e) {
                Remold.LOG.error("Could not list {}", packs, e);
            }
        }
        return result;
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE) throw new IOException("File too large: " + file.getFileName());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path resolve(Path root, String relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (relative.contains("..") || !file.startsWith(root.normalize())) throw new IOException("Invalid path " + relative);
        return file;
    }

    private static List<Recipe0> packRecipes(Path pack) throws IOException {
        List<Recipe0> list = new ArrayList<>();
        Path data = pack.resolve("data");
        if (!Files.isDirectory(data)) return list;
        try (Stream<Path> files = Files.walk(data)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json") && Files.isRegularFile(f)).sorted().toList()) {
                ResourceLocation id = idOf(data.relativize(file).toString().replace('\\', '/'));
                if (id != null) list.add(new Recipe0(id, file, read(file)));
            }
        }
        return list;
    }

    private static List<Recipe0> zipRecipes(Path zip) throws IOException {
        List<Recipe0> list = new ArrayList<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.startsWith("data/") || !name.endsWith(".json")) continue;
                byte[] bytes = zis.readNBytes((int) MAX_FILE + 1);
                if (bytes.length > MAX_FILE) throw new IOException("File too large in pack: " + name);
                ResourceLocation id = idOf(name.substring("data/".length()));
                if (id != null) list.add(new Recipe0(id, null, new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        list.sort(Comparator.comparing(r -> r.id().toString()));
        return list;
    }

    @Nullable
    private static ResourceLocation idOf(String underData) {
        String[] parts = underData.split("/");
        if (parts.length < 3 || !(parts[1].equals("recipe") || parts[1].equals("recipes"))) return null;
        String path = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
        return ResourceLocation.tryBuild(parts[0], path.substring(0, path.length() - ".json".length()));
    }

    private static String problemOf(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return "invalid";
            JsonObject o = e.getAsJsonObject();
            if (!o.keySet().isEmpty() && CONDITION_KEYS.containsAll(o.keySet())) return "removal";
            return "";
        } catch (Exception e) {
            return "invalid";
        }
    }

    private static String outputOf(String json) {
        try {
            return KubeJsScript.outputOfJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Recipe0> recipes(MinecraftServer server, String key) throws IOException {
        if (key.startsWith(PACK)) return packRecipes(resolve(datapacks(server), key.substring(PACK.length())));
        if (key.startsWith(ZIP)) return zipRecipes(resolve(datapacks(server), key.substring(ZIP.length())));
        throw new IOException("Unknown source " + key);
    }

    private static ResourceLocation scriptId(String key, int index) {
        String clean = key.substring(KUBEJS.length()).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_").replace(".js", "");
        return ResourceLocation.fromNamespaceAndPath("kubejs", "remold/" + clean + "/" + index);
    }

    public static List<Payloads.SourceEntry> entries(MinecraftServer server, String key) throws IOException {
        List<Payloads.SourceEntry> list = new ArrayList<>();
        if (key.startsWith(KUBEJS)) {
            List<KubeJsScript.Entry> entries = KubeJsScript.parse(read(resolve(scripts(server), key.substring(KUBEJS.length()))));
            for (int i = 0; i < entries.size(); i++) {
                KubeJsScript.Entry e = entries.get(i);
                String id = e.id() != null ? e.id() : scriptId(key, i).toString();
                list.add(new Payloads.SourceEntry(i, id, e.output(), e.problem()));
            }
            return list;
        }
        List<Recipe0> recipes = recipes(server, key);
        for (int i = 0; i < recipes.size(); i++) {
            Recipe0 r = recipes.get(i);
            list.add(new Payloads.SourceEntry(i, r.id().toString(), outputOf(r.json()), problemOf(r.json())));
        }
        return list;
    }

    public static void handleRequest(ServerPlayer player, Payloads.SourceRequest request) {
        MinecraftServer server = player.server;
        try {
            if (request.key().isEmpty()) {
                Services.PLATFORM.sendToPlayer(player, new Payloads.SourceList(list(server)));
            } else if (request.index() < 0) {
                Services.PLATFORM.sendToPlayer(player, new Payloads.SourceEntries(request.key(), entries(server, request.key())));
            } else {
                sendRecipe(player, request.key(), request.index());
            }
        } catch (IOException e) {
            ServerActions.notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
        }
    }

    private static void sendRecipe(ServerPlayer player, String key, int index) throws IOException {
        MinecraftServer server = player.server;
        if (key.startsWith(KUBEJS)) {
            List<KubeJsScript.Entry> entries = KubeJsScript.parse(read(resolve(scripts(server), key.substring(KUBEJS.length()))));
            if (index >= entries.size() || !entries.get(index).editable()) throw new IOException("This recipe can't be edited in Remold");
            KubeJsScript.Entry e = entries.get(index);
            String id = e.id() != null ? e.id() : scriptId(key, index).toString();
            Services.PLATFORM.sendToPlayer(player, new Payloads.SourceRecipe(key, index, id, e.json().toString()));
            return;
        }
        List<Recipe0> recipes = recipes(server, key);
        if (index >= recipes.size() || !problemOf(recipes.get(index).json()).isEmpty()) throw new IOException("This recipe can't be edited in Remold");
        Recipe0 r = recipes.get(index);
        Services.PLATFORM.sendToPlayer(player, new Payloads.SourceRecipe(key, index, r.id().toString(), r.json()));
    }

    public static void handleSave(ServerPlayer player, Payloads.SourceSave save) {
        MinecraftServer server = player.server;
        JsonObject json;
        try {
            json = JsonParser.parseString(save.json()).getAsJsonObject();
        } catch (Exception e) {
            ServerActions.notice(player, false, "invalid", "invalid_json", String.valueOf(e.getMessage()));
            return;
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var parsed = Recipe.CODEC.parse(ops, json);
        if (parsed.error().isPresent()) {
            ServerActions.notice(player, false, "invalid", "plain", parsed.error().get().message());
            return;
        }
        RemoldPack pack = RemoldPack.get(server);
        String key = save.key();
        String name = key.substring(key.indexOf('/') + 1);
        try {
            pack.beginBatch(player.getGameProfile().getName());
            if (key.startsWith(KUBEJS)) {
                Path file = resolve(scripts(server), key.substring(KUBEJS.length()));
                String src = read(file);
                List<KubeJsScript.Entry> entries = KubeJsScript.parse(src);
                if (save.index() >= entries.size() || !entries.get(save.index()).editable()) throw new IOException("This recipe can't be edited in Remold");
                KubeJsScript.Entry e = entries.get(save.index());
                String js = KubeJsScript.toJs(e.receiver(), json, e.id());
                pack.writeExternal(file, src.substring(0, e.start()) + js + src.substring(e.end()));
            } else if (key.startsWith(PACK)) {
                List<Recipe0> recipes = recipes(server, key);
                if (save.index() >= recipes.size()) throw new IOException("Recipe not found");
                pack.writeExternal(recipes.get(save.index()).file(), RemoldPack.GSON.toJson(json));
            } else {
                List<Recipe0> recipes = recipes(server, key);
                if (save.index() >= recipes.size()) throw new IOException("Recipe not found");
                ResourceLocation id = recipes.get(save.index()).id();
                String type = json.has("type") ? json.get("type").getAsString() : "";
                pack.writeRecipe(id, json, ServerActions.entry(player, id, ChangeEntry.Kind.REPLACED, type));
            }
        } catch (IOException e) {
            ServerActions.notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        ServerActions.finish(player, save.apply(), "source_saved", name);
        try {
            Services.PLATFORM.sendToPlayer(player, new Payloads.SourceEntries(key, entries(server, key)));
        } catch (IOException ignored) {
        }
    }
}
