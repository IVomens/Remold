package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** One change Remold has made to the recipe set, as recorded in the pack's manifest. */
public record ChangeEntry(ResourceLocation id, Kind kind, String recipeType, String author, long time) {

    public enum Kind implements StringRepresentable {
        /** A brand new recipe id. */
        ADDED,
        /** A recipe that replaces an existing recipe with the same id (from vanilla or another mod). */
        REPLACED,
        /** An existing recipe that has been disabled. */
        REMOVED;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Codec<ChangeEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(ChangeEntry::id),
            Kind.CODEC.fieldOf("kind").forGetter(ChangeEntry::kind),
            Codec.STRING.optionalFieldOf("recipe_type", "").forGetter(ChangeEntry::recipeType),
            Codec.STRING.optionalFieldOf("author", "").forGetter(ChangeEntry::author),
            Codec.LONG.optionalFieldOf("time", 0L).forGetter(ChangeEntry::time)
    ).apply(i, ChangeEntry::new));

    public static final StreamCodec<ByteBuf, ChangeEntry> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ChangeEntry::id,
            ByteBufCodecs.VAR_INT.map(o -> Kind.values()[o], Kind::ordinal), ChangeEntry::kind,
            ByteBufCodecs.STRING_UTF8, ChangeEntry::recipeType,
            ByteBufCodecs.STRING_UTF8, ChangeEntry::author,
            ByteBufCodecs.VAR_LONG, ChangeEntry::time,
            ChangeEntry::new);
}
