package dev.sbs.api.io.gson.factory;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.io.gson.PostInit;
import lombok.extern.log4j.Log4j2;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

@Log4j2
public class PostInitTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> type) {
        if (!PostInit.class.isAssignableFrom(type.getRawType()))
            return null;

        final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

        return new TypeAdapter<>() {

            @Override
            public void write(JsonWriter out, T value) throws IOException {
                delegate.write(out, value);
            }

            @Override
            public T read(JsonReader in) throws IOException {
                T obj = delegate.read(in);

                try {
                    ((PostInit) obj).postInit();
                } catch (Exception ex) {
                    log.error("Exception during postInit of {}: {}", obj.getClass().getName(), ex.getMessage(), ex);
                }

                return obj;
            }

        };
    }

}
