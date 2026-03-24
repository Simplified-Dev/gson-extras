package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.math.Vector4f;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter that serializes a {@link Vector4f} as a four-element JSON array
 * {@code [x, y, z, w]} and deserializes from the same format.
 */
@NoArgsConstructor
public final class Vector4fTypeAdapter extends TypeAdapter<Vector4f> {

    @Override
    public void write(@NotNull JsonWriter out, @Nullable Vector4f value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.beginArray();
        out.value(value.getX());
        out.value(value.getY());
        out.value(value.getZ());
        out.value(value.getW());
        out.endArray();
    }

    @Override
    public @Nullable Vector4f read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        in.beginArray();
        float x = (float) in.nextDouble();
        float y = (float) in.nextDouble();
        float z = (float) in.nextDouble();
        float w = (float) in.nextDouble();
        in.endArray();

        return new Vector4f(x, y, z, w);
    }

}
