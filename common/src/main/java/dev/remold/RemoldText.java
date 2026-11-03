package dev.remold;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;

public final class RemoldText {
    private RemoldText() {}

    public static MutableComponent tr(String key, Object... args) {
        return Component.translatable(Remold.MOD_ID + "." + key, args);
    }

    public static String error(String key, Object... args) {
        StringBuilder sb = new StringBuilder(Remold.MOD_ID).append(".error.").append(key);
        for (Object arg : args) sb.append('|').append(arg);
        return sb.toString();
    }

    public static Component message(String raw) {
        if (raw.startsWith(Remold.MOD_ID + ".")) {
            String[] parts = raw.split("\\|");
            return Component.translatable(parts[0], (Object[]) Arrays.copyOfRange(parts, 1, parts.length));
        }
        return Component.literal(raw);
    }
}
