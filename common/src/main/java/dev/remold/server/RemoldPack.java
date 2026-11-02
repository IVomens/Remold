package dev.remold.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The world-local datapack that holds everything made with Remold:
 * <pre>
 * world/datapacks/remold/
 *   pack.mcmeta
 *   remold_manifest.json      what was changed, by whom and when
 *   remold_annotations.json   item tooltips / hidden items
 *   data/&lt;namespace&gt;/recipe/&lt;path&gt;.json
 * </pre>
 * Because the result is a plain datapack, recipes keep working even if Remold is uninstalled, and
 * every mod that reads recipes (JEI, EMI, REI, machines, ...) sees them like any other recipe.
 */
public final class RemoldPack {
    public static final String FOLDER = "remold";
    public static final String PACK_ID = "file/" + FOLDER;
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Codec<List<ChangeEntry>> MANIFEST_CODEC = ChangeEntry.CODEC.listOf();
    private static final Codec<Map<ResourceLocation, Annotation>> ANNOTATIONS_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, Annotation.CODEC);

    private final MinecraftServer server;
    private final Path root;
    private final Map<ResourceLocation, ChangeEntry> changes = new LinkedHashMap<>();
    private final Map<ResourceLocation, Annotation> annotations = new TreeMap<>();
    private boolean pendingApply;
    private CompletableFuture<Void> reloading;
    private boolean reloadQueued;

    private static RemoldPack instance;

    private RemoldPack(MinecraftServer server) {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(FOLDER);
        load();
    }

    public static RemoldPack get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new RemoldPack(server);
        }
        return instance;
    }

    public static void onServerStopped() {
        instance = null;
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ loading / saving

    private void load() {
        readJson(root.resolve("remold_manifest.json")).ifPresent(json ->
                MANIFEST_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_manifest.json: {}", e))
                        .ifPresent(list -> list.forEach(c -> changes.put(c.id(), c))));

        // Pack-wide defaults (shipped by modpacks) first, then the world's own annotations on top.
        Path global = dev.remold.platform.Services.PLATFORM.configDir().resolve(Remold.MOD_ID).resolve("annotations.json");
        for (Path file : List.of(global, root.resolve("remold_annotations.json"))) {
            readJson(file).ifPresent(json -> ANNOTATIONS_CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", file, e))
                    .ifPresent(annotations::putAll));
        }
    }

    private static Optional<JsonElement> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Remold.LOG.error("Could not read {}", file, e);
            return Optional.empty();
        }
    }

    private void writeJson(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private void ensurePack() throws IOException {
        Path mcmeta = root.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
            pack.addProperty("description", "Recipes made in-game with Remold");
            JsonObject meta = new JsonObject();
            meta.add("pack", pack);
            writeJson(mcmeta, meta);
        }
    }

    private void saveManifest() throws IOException {
        List<ChangeEntry> list = new ArrayList<>(changes.values());
        list.sort(Comparator.comparing(c -> c.id().toString()));
        writeJson(root.resolve("remold_manifest.json"),
                MANIFEST_CODEC.encodeStart(JsonOps.INSTANCE, list).getOrThrow());
    }

    private void saveAnnotations() throws IOException {
        ensurePack();
        writeJson(root.resolve("remold_annotations.json"),
                ANNOTATIONS_CODEC.encodeStart(JsonOps.INSTANCE, annotations).getOrThrow());
    }

    public Path recipeFile(ResourceLocation id) {
        return root.resolve("data").resolve(id.getNamespace()).resolve("recipe").resolve(id.getPath() + ".json");
    }

    // ------------------------------------------------------------------ recipe changes

    public void writeRecipe(ResourceLocation id, JsonElement json, ChangeEntry entry) throws IOException {
        ensurePack();
        writeJson(recipeFile(id), json);
        changes.put(id, entry);
        saveManifest();
        pendingApply = true;
    }

    /** Deletes Remold's file for this id, restoring whatever the id was before. */
    public boolean revert(ResourceLocation id) throws IOException {
        boolean existed = Files.deleteIfExists(recipeFile(id));
        existed |= changes.remove(id) != null;
        if (existed) {
            saveManifest();
            pendingApply = true;
        }
        return existed;
    }

    public Optional<ChangeEntry> change(ResourceLocation id) {
        return Optional.ofNullable(changes.get(id));
    }

    public List<ChangeEntry> changes() {
        return List.copyOf(changes.values());
    }

    public boolean pendingApply() {
        return pendingApply;
    }

    // ------------------------------------------------------------------ annotations

    public Map<ResourceLocation, Annotation> annotations() {
        return Map.copyOf(annotations);
    }

    public void setAnnotation(ResourceLocation item, Annotation annotation) throws IOException {
        if (annotation.isEmpty()) {
            annotations.remove(item);
        } else {
            annotations.put(item, annotation);
        }
        saveAnnotations();
    }

    // ------------------------------------------------------------------ applying

    /**
     * Makes sure the pack is enabled (and has the highest priority) and reloads datapacks, exactly like
     * {@code /reload}. Concurrent requests are coalesced into one extra reload.
     */
    public CompletableFuture<Void> apply() {
        if (reloading != null && !reloading.isDone()) {
            reloadQueued = true;
            return reloading;
        }
        PackRepository repo = server.getPackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove(PACK_ID);
        if (repo.isAvailable(PACK_ID)) {
            selected.add(PACK_ID);
        }
        pendingApply = false;
        reloading = server.reloadResources(selected).whenComplete((v, err) -> server.execute(() -> {
            if (err != null) {
                Remold.LOG.error("Reload failed", err);
            }
            if (reloadQueued) {
                reloadQueued = false;
                apply();
            }
        }));
        return reloading;
    }
}
