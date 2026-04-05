package dev.simplified.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

@RequiredArgsConstructor
public final class StringTypeAdapter extends TypeAdapter<String> {

    private final @NotNull GsonSettings.StringType stringType;

    @Override
    public void write(@NotNull JsonWriter out, @NotNull String value) throws IOException {
        if (StringUtil.isEmpty(value)) {
            if (this.stringType == GsonSettings.StringType.EMPTY) {
                out.value("");
                return;
            } else if (this.stringType == GsonSettings.StringType.NULL) {
                out.nullValue();
                return;
            }
        }

        out.value(value);
    }

    @Override
    public @Nullable String read(@NotNull JsonReader in) throws IOException {
        if (in.peek() != com.google.gson.stream.JsonToken.STRING)
            return null;

        String value = in.nextString();

        if (StringUtil.isEmpty(value)) {
            if (this.stringType == GsonSettings.StringType.EMPTY)
                return "";
            else if (this.stringType == GsonSettings.StringType.NULL)
                return null;
        }

        return value;
    }

}
