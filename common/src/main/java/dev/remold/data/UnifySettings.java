package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public record UnifySettings(Map<ResourceLocation, ResourceLocation> groups, boolean hide, boolean convert, boolean tagInputs) {
    public static final UnifySettings EMPTY = new UnifySettings(Map.of(), true, false, true);

    public static final Codec<UnifySettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ResourceLocation.CODEC, ResourceLocation.CODEC).optionalFieldOf("groups", Map.of()).forGetter(UnifySettings::groups),
            Codec.BOOL.optionalFieldOf("hide_others", true).forGetter(UnifySettings::hide),
            Codec.BOOL.optionalFieldOf("convert_inventories", false).forGetter(UnifySettings::convert),
            Codec.BOOL.optionalFieldOf("ingredients_accept_tag", true).forGetter(UnifySettings::tagInputs)
    ).apply(i, UnifySettings::new));

    public static final StreamCodec<ByteBuf, UnifySettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<ByteBuf, ResourceLocation, ResourceLocation, Map<ResourceLocation, ResourceLocation>>map(HashMap::new,
                    ResourceLocation.STREAM_CODEC, ResourceLocation.STREAM_CODEC), UnifySettings::groups,
            ByteBufCodecs.BOOL, UnifySettings::hide,
            ByteBufCodecs.BOOL, UnifySettings::convert,
            ByteBufCodecs.BOOL, UnifySettings::tagInputs,
            UnifySettings::new);

    public UnifySettings {
        groups = Map.copyOf(groups);
    }
}
