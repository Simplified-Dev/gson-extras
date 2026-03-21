package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;

@NoArgsConstructor
public final class InstantTypeAdapter extends TypeAdapter<Instant> {

    @Override
    public void write(@NotNull JsonWriter out, @NotNull Instant value) throws IOException {
        out.value(value.toEpochMilli());
    }

    @Override
    public Instant read(@NotNull JsonReader in) throws IOException {
        return Instant.ofEpochMilli(in.nextLong());
    }

}
