package dev.remold.platform;

import com.google.gson.JsonObject;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Everything Remold needs from the mod loader. Porting Remold to a new loader means implementing
 * this interface (registered through {@code META-INF/services}) and forwarding a handful of events.
 */
public interface RemoldPlatform {
    String loaderName();

    boolean isModLoaded(String modId);

    /** Human readable name for a mod id / namespace, falling back to the namespace itself. */
    String modName(String namespace);

    Path configDir();

    void sendToServer(CustomPacketPayload payload);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * A recipe JSON body that makes the loader skip the recipe entirely. Writing this over an
     * existing recipe id in a higher priority datapack removes that recipe.
     */
    JsonObject disabledRecipeJson();

    /**
     * The tag file that applies Remold's edits to a tag.
     *
     * @param add     entries to add (item ids, or {@code #tag} ids)
     * @param remove  entries to remove
     * @param current the tag's current contents (item ids), for loaders without tag removal support
     */
    JsonObject tagJson(java.util.List<String> add, java.util.List<String> remove, java.util.List<String> current);
}
