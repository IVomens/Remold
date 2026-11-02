package dev.remold.platform;

import java.util.ServiceLoader;

public final class Services {
    public static final RemoldPlatform PLATFORM = load(RemoldPlatform.class);

    private Services() {}

    private static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation of " + type.getName() + " found"));
    }
}
