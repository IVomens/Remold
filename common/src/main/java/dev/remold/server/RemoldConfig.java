package dev.remold.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.remold.Remold;
import dev.remold.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record RemoldConfig(int permissionLevel, List<String> allowedPlayers) {
    public static final RemoldConfig DEFAULT = new RemoldConfig(Remold.PERMISSION_LEVEL, List.of());

    public static final Codec<RemoldConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, 4).optionalFieldOf("permission_level", Remold.PERMISSION_LEVEL).forGetter(RemoldConfig::permissionLevel),
            Codec.STRING.listOf().optionalFieldOf("allowed_players", List.of()).forGetter(RemoldConfig::allowedPlayers)
    ).apply(i, RemoldConfig::new));

    private static RemoldConfig current = DEFAULT;

    public static RemoldConfig get() {
        return current;
    }

    public static void load() {
        Path file = Services.PLATFORM.configDir().resolve(Remold.MOD_ID).resolve("remold.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());

                JsonObject json = new JsonObject();
                json.addProperty("permission_level", DEFAULT.permissionLevel());
                json.add("allowed_players", new JsonArray());
                Files.writeString(file, RemoldPack.GSON.toJson(json), StandardCharsets.UTF_8);
                current = DEFAULT;
                return;
            }
            current = CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)))
                    .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", file, e)).orElse(DEFAULT);
        } catch (Exception e) {
            Remold.LOG.error("Could not read {}", file, e);
            current = DEFAULT;
        }
    }

    public boolean listed(ServerPlayer player) {
        String name = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
        String uuid = player.getUUID().toString();
        for (String entry : allowedPlayers) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.equals(name) || e.equals(uuid)) return true;
        }
        return false;
    }

    public boolean allowed(ServerPlayer player) {
        return listed(player) || player.hasPermissions(permissionLevel);
    }

    public boolean allowed(CommandSourceStack source) {
        if (source.getPlayer() != null) return allowed(source.getPlayer());
        return source.hasPermission(permissionLevel);
    }
}
