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
import dev.remold.data.HistoryEntry;
import dev.remold.data.TagEdit;
import dev.remold.data.UnifySettings;
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
 *   remold_history.json       previous versions, for undo
 *   remold_tags.json          tag edits
 *   remold_unify.json         item unification settings
 *   data/&lt;namespace&gt;/tags/item/&lt;path&gt;.json
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
    private static final int MAX_HISTORY = 1000;
    private static final Codec<List<HistoryEntry>> HISTORY_CODEC = HistoryEntry.CODEC.listOf();
    private static final Codec<Map<ResourceLocation, TagEdit>> TAGS_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, TagEdit.CODEC);
    private static final Codec<Map<ResourceLocation, Annotation>> ANNOTATIONS_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, Annotation.CODEC);

    private final MinecraftServer server;
    private final Path root;
    private final Map<ResourceLocation, ChangeEntry> changes = new LinkedHashMap<>();
    private final Map<ResourceLocation, Annotation> annotations = new TreeMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Map<ResourceLocation, TagEdit> tagEdits = new TreeMap<>();
    private UnifySettings unify = UnifySettings.EMPTY;
    private static final String UNIFY_FILE = "remold_unify.json";
    private long batch;
    private String batchAuthor = "";
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

    public static void onServerStarting() {
        RemoldConfig.load();
    }

    public static void onServerTick(MinecraftServer server) {
        Unifier.tick(server);
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

        readJson(root.resolve("remold_history.json")).ifPresent(json ->
                HISTORY_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_history.json: {}", e))
                        .ifPresent(history::addAll));

        readJson(root.resolve("remold_tags.json")).ifPresent(json ->
                TAGS_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_tags.json: {}", e))
                        .ifPresent(tagEdits::putAll));

        loadUnify();

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

    private void saveHistory() throws IOException {
        while (history.size() > MAX_HISTORY) history.removeFirst();
        writeJson(root.resolve("remold_history.json"), HISTORY_CODEC.encodeStart(JsonOps.INSTANCE, history).getOrThrow());
    }

    // ------------------------------------------------------------------ undo

    /** Starts a new undo step. Everything changed until the next call is undone together. */
    public void beginBatch(String author) {
        batch = (history.isEmpty() ? 0 : history.getLast().batch()) + 1;
        batchAuthor = author;
    }

    private void rememberRecipe(ResourceLocation id) throws IOException {
        Path file = recipeFile(id);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, id, previous, Optional.ofNullable(changes.get(id)),
                Optional.empty(), batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void rememberAnnotation(ResourceLocation item) throws IOException {
        history.add(new HistoryEntry(batch, true, item, Optional.empty(), Optional.empty(),
                Optional.ofNullable(annotations.get(item)), batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void rememberTag(ResourceLocation tag) throws IOException {
        Path file = tagFile(tag);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, tag, previous, Optional.empty(), Optional.empty(),
                batchAuthor, System.currentTimeMillis(), true, Optional.ofNullable(tagEdits.get(tag)), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void loadUnify() {
        unify = readJson(root.resolve(UNIFY_FILE))
                .flatMap(json -> UnifySettings.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", UNIFY_FILE, e)))
                .orElse(UnifySettings.EMPTY);
        Unifier.invalidate();
    }

    public UnifySettings unify() {
        return unify;
    }

    /** Stores the unification settings (undoable with the rest of the current batch). */
    public void setUnify(UnifySettings settings) throws IOException {
        ensurePack();
        Path file = root.resolve(UNIFY_FILE);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, Remold.id("unify"), previous, Optional.empty(), Optional.empty(),
                batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.of(UNIFY_FILE)));
        saveHistory();
        unify = settings;
        Unifier.invalidate();
        writeJson(file, UnifySettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow());
    }

    public record UndoResult(int changes, boolean recipes, boolean annotations, String author) {}

    /** Undoes the most recent step (one save, removal, "make unobtainable", ...). */
    public Optional<UndoResult> undo() throws IOException {
        if (history.isEmpty()) return Optional.empty();
        long last = history.getLast().batch();
        int count = 0;
        boolean recipes = false, notes = false;
        String author = history.getLast().author();
        while (!history.isEmpty() && history.getLast().batch() == last) {
            HistoryEntry entry = history.removeLast();
            count++;
            if (entry.configFile().isPresent()) {
                Path file = root.resolve(entry.configFile().get());
                if (entry.previousFile().isPresent()) Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
                else Files.deleteIfExists(file);
                loadUnify();
                notes = true;
                continue;
            }
            if (entry.annotation()) {
                notes = true;
                entry.previousAnnotation().ifPresentOrElse(a -> annotations.put(entry.id(), a), () -> annotations.remove(entry.id()));
                continue;
            }
            if (entry.tag()) {
                recipes = true; // tags need a reload too
                Path file = tagFile(entry.id());
                if (entry.previousFile().isPresent()) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(file);
                }
                entry.previousTagEdit().ifPresentOrElse(e -> tagEdits.put(entry.id(), e), () -> tagEdits.remove(entry.id()));
                saveTags();
                continue;
            }
            recipes = true;
            Path file = recipeFile(entry.id());
            if (entry.previousFile().isPresent()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(file);
            }
            entry.previousChange().ifPresentOrElse(c -> changes.put(entry.id(), c), () -> changes.remove(entry.id()));
        }
        saveHistory();
        if (recipes) {
            saveManifest();
            pendingApply = true;
        }
        if (notes) saveAnnotations();
        return Optional.of(new UndoResult(count, recipes, notes, author));
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }

    public Path tagFile(ResourceLocation tag) {
        return root.resolve("data").resolve(tag.getNamespace()).resolve("tags").resolve("item").resolve(tag.getPath() + ".json");
    }

    private void saveTags() throws IOException {
        writeJson(root.resolve("remold_tags.json"), TAGS_CODEC.encodeStart(JsonOps.INSTANCE, tagEdits).getOrThrow());
    }

    public Map<ResourceLocation, TagEdit> tagEdits() {
        return Map.copyOf(tagEdits);
    }

    /**
     * Stores the complete set of edits for one item tag and writes its tag file.
     *
     * @param current the tag's current contents, for loaders that can't remove entries from tags
     */
    public void setTagEdit(ResourceLocation tag, TagEdit edit, List<String> current) throws IOException {
        ensurePack();
        rememberTag(tag);
        if (edit.isEmpty()) {
            tagEdits.remove(tag);
            Files.deleteIfExists(tagFile(tag));
        } else {
            // "current" includes the effect of Remold's previous edit. Undo that part, so loaders that rewrite
            // the whole tag (Fabric) restore entries that are no longer removed and drop ones no longer added.
            TagEdit previous = tagEdits.getOrDefault(tag, TagEdit.EMPTY);
            List<String> base = new ArrayList<>(current);
            for (String r : previous.remove()) if (!edit.remove().contains(r) && !r.startsWith("#") && !base.contains(r)) base.add(r);
            for (String a : previous.add()) if (!edit.add().contains(a)) base.remove(a);
            tagEdits.put(tag, edit);
            writeJson(tagFile(tag), dev.remold.platform.Services.PLATFORM.tagJson(edit.add(), edit.remove(), base));
        }
        saveTags();
        pendingApply = true;
    }

    public Path recipeFile(ResourceLocation id) {
        return root.resolve("data").resolve(id.getNamespace()).resolve("recipe").resolve(id.getPath() + ".json");
    }

    // ------------------------------------------------------------------ recipe changes

    public void writeRecipe(ResourceLocation id, JsonElement json, ChangeEntry entry) throws IOException {
        ensurePack();
        rememberRecipe(id);
        writeJson(recipeFile(id), json);
        changes.put(id, entry);
        saveManifest();
        pendingApply = true;
    }

    /** Deletes Remold's file for this id, restoring whatever the id was before. */
    public boolean revert(ResourceLocation id) throws IOException {
        if (Files.exists(recipeFile(id)) || changes.containsKey(id)) rememberRecipe(id);
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
        rememberAnnotation(item);
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
