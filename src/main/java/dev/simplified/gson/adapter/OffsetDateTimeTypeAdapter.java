package dev.simplified.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@NoArgsConstructor
public final class OffsetDateTimeTypeAdapter extends TypeAdapter<OffsetDateTime> {

    @Override
    public void write(@NotNull JsonWriter out, @NotNull OffsetDateTime value) throws IOException {
        out.value(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
    }

    @Override
    public OffsetDateTime read(@NotNull JsonReader in) throws IOException {
        return OffsetDateTime.parse(in.nextString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

}
