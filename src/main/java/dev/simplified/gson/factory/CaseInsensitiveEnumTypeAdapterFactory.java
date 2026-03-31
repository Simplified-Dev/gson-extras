package dev.sbs.api.io.gson.factory;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link TypeAdapterFactory} that deserializes enum constants using
 * case-insensitive name matching.
 * <p>
 * On serialization, constants annotated with {@link SerializedName} use
 * their declared serialized name; all others use {@link Enum#name()}.
 * On deserialization, incoming values are matched against constant names
 * and {@link SerializedName} values (including alternates) without
 * regard to case.
 */
public final class CaseInsensitiveEnumTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> type) {
        Class<T> rawType = (Class<T>) type.getRawType();

        if (!rawType.isEnum())
            return null;

        return (TypeAdapter<T>) new CaseInsensitiveEnumTypeAdapter(rawType);
    }

    private static class CaseInsensitiveEnumTypeAdapter<E extends Enum<E>> extends TypeAdapter<E> {

        private final Map<String, E> nameToConstant = new HashMap<>();
        private final Map<E, String> constantToName = new HashMap<>();

        CaseInsensitiveEnumTypeAdapter(@NotNull Class<E> enumClass) {
            for (E constant : enumClass.getEnumConstants()) {
                String writeName = constant.name();

                try {
                    SerializedName annotation = enumClass.getField(constant.name()).getAnnotation(SerializedName.class);

                    if (annotation != null) {
                        writeName = annotation.value();

                        for (String alternate : annotation.alternate())
                            nameToConstant.put(alternate.toUpperCase(), constant);
                    }
                } catch (NoSuchFieldException ignored) { }

                constantToName.put(constant, writeName);
                nameToConstant.put(writeName.toUpperCase(), constant);
                nameToConstant.put(constant.name().toUpperCase(), constant);
            }
        }

        @Override
        public void write(@NotNull JsonWriter out, @Nullable E value) throws IOException {
            if (value == null)
                out.nullValue();
            else
                out.value(constantToName.get(value));
        }

        @Override
        public @Nullable E read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            return nameToConstant.get(in.nextString().toUpperCase());
        }

    }

}
