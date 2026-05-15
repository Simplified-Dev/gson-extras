package dev.simplified.gson.adapter;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Gson {@link TypeAdapter} for {@link Instant} values serialized in RFC 822 / RFC 2822
 * date format, the canonical date representation used by RSS 2.0 {@code <pubDate>} and
 * {@code <lastBuildDate>} fields.
 * <p>
 * The format looks like {@code Sun, 05 Apr 2026 12:56:02 +0000}. Java's
 * {@link DateTimeFormatter#RFC_1123_DATE_TIME} parses both RFC 822 and its successor
 * RFC 1123/2822 forms, which covers every feed generator encountered in the wild.
 * <p>
 * Applied per-field via {@link JsonAdapter @JsonAdapter} so
 * the global {@link Gson Gson} instance can continue using its
 * default handling for every other {@link Instant} field across the codebase
 * (epoch-millis for Hypixel API responses, ISO-8601 elsewhere).
 */
@NoArgsConstructor
public final class Rfc822InstantAdapter extends TypeAdapter<Instant> {

    private static final @NotNull DateTimeFormatter FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    @Override
    public void write(@NotNull JsonWriter out, @Nullable Instant value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.value(FORMAT.format(value.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public @Nullable Instant read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        return ZonedDateTime.parse(in.nextString(), FORMAT).toInstant();
    }

}
