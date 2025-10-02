package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.io.gson.GsonSettings;
import dev.sbs.api.util.StringUtil;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

@RequiredArgsConstructor
public class StringTypeAdapter extends TypeAdapter<String> {

    private final GsonSettings.StringType stringType;

    @Override
    public void write(JsonWriter out, String value) throws IOException {
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
    public String read(JsonReader in) throws IOException {
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
