package dev.simplified.gson.factory;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

@RequiredArgsConstructor
public final class OptionalTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("all")
    public <T> @Nullable TypeAdapter<T> create(Gson gson, @NotNull TypeToken<T> type) {
        Class<T> rawType = (Class<T>) type.getRawType();
        if (rawType != Optional.class)
            return null;

        Type typeFix = type.getType() == Optional.class ? new TypeToken<Optional<?>>() {}.getType() : type.getType();
        final ParameterizedType parameterizedType = (ParameterizedType) typeFix;
        final Type actualType = parameterizedType.getActualTypeArguments()[0];
        final TypeAdapter<?> adapter = gson.getAdapter(TypeToken.get(actualType));
        return new OptionalTypeAdapter(adapter);
    }

    @Getter
    @RequiredArgsConstructor
    private static class OptionalTypeAdapter<T> extends TypeAdapter<Optional<T>> {

        private final @NotNull TypeAdapter<T> adapter;

        @Override
        public void write(JsonWriter out, @NotNull Optional<T> value) throws IOException {
            if (value.isPresent())
                this.adapter.write(out, value.get());
            else
                out.nullValue();
        }

        @Override
        public Optional<T> read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.NULL)
                return Optional.ofNullable(this.adapter.read(in));
            else {
                in.nextNull();
                return Optional.empty();
            }
        }

    }

}
