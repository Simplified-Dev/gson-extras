package dev.sbs.api.io.gson.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.util.StringUtil;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;

@NoArgsConstructor
public class ColorTypeAdapter extends TypeAdapter<Color> {

    @Override
    public void write(@NotNull JsonWriter out, @NotNull Color value) throws IOException {
        int rgba = (value.getRed() << 24) |
            (value.getGreen() << 16) |
            (value.getBlue() << 8) |
            value.getAlpha();

        out.value(rgba);
    }

    @Override
    public Color read(@NotNull JsonReader in) throws IOException {
        try {
            return new Color(in.nextInt(), true);
        } catch (Exception ex) {
            String[] parts = StringUtil.split(in.nextString(), ',');
            int r = Integer.parseInt(parts[0]);
            int g = Integer.parseInt(parts[1]);
            int b = Integer.parseInt(parts[2]);
            int a = parts.length == 4 ? Integer.parseInt(parts[3]) : 255;
            return new Color(r, g, b, a);
        }
    }

}
