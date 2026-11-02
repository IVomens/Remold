package dev.remold.core;

import dev.remold.RemoldText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Template recipes fetched from the server. The server encodes its own copy of the recipe, which
 * still has everything the original JSON had (tags, shaped keys, ...), unlike the client's copy.
 */
public final class TemplateCache {
    private static final Map<ResourceLocation, DataResult<TemplateRecipe>> CACHE = new HashMap<>();
    private static final Set<ResourceLocation> REQUESTED = new HashSet<>();

    private TemplateCache() {}

    /** The template, or empty while it is being fetched (a request is sent the first time). */
    public static Optional<DataResult<TemplateRecipe>> get(ResourceLocation id) {
        DataResult<TemplateRecipe> cached = CACHE.get(id);
        if (cached != null) return Optional.of(cached);
        if (REQUESTED.add(id)) {
            Services.PLATFORM.sendToServer(new Payloads.RequestTemplate(id));
        }
        return Optional.empty();
    }

    public static void receive(Payloads.TemplateJson payload) {
        REQUESTED.remove(payload.id());
        if (!payload.error().isEmpty()) {
            CACHE.put(payload.id(), DataResult.error(payload::error));
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(payload.json()).getAsJsonObject();
            CACHE.put(payload.id(), DataResult.success(TemplateRecipe.of(payload.id(), json)));
        } catch (Exception e) {
            CACHE.put(payload.id(), DataResult.error(() -> RemoldText.error("bad_template", e.getMessage())));
        }
    }

    /** Recipes changed on the server: forget everything. */
    public static void clear() {
        CACHE.clear();
        REQUESTED.clear();
    }
}
