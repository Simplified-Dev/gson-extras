package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.math.Vector2f;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter that serializes a {@link Vector2f} as a three-element JSON array
 * {@code [x, y]} and deserializes from the same format.
 */
@NoArgsConstructor
public final class Vector2fTypeAdapter extends TypeAdapter<Vector2f> {

    @Override
    public void write(@NotNull JsonWriter out, @Nullable Vector2f value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.beginArray();
        out.value(value.getX());
        out.value(value.getY());
        out.endArray();
    }

    @Override
    public @Nullable Vector2f read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        in.beginArray();
        float x = (float) in.nextDouble();
        float y = (float) in.nextDouble();
        in.endArray();

        return new Vector2f(x, y);
    }

}
