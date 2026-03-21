package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.util.StringUtil;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.UUID;

@NoArgsConstructor
public class UUIDTypeAdapter extends TypeAdapter<UUID> {

    @Override
    public void write(@NotNull JsonWriter out, @NotNull UUID value) throws IOException {
        out.value(value.toString());
    }

    @Override
    public UUID read(@NotNull JsonReader in) throws IOException {
        return StringUtil.toUUID(in.nextString());
    }

}
