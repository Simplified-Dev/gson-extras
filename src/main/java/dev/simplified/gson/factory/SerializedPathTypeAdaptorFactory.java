package dev.simplified.gson.factory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.SerializedPath;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

@NoArgsConstructor
public final class SerializedPathTypeAdaptorFactory implements TypeAdapterFactory {

    @Override
    public <T> @NotNull TypeAdapter<T> create(final @NotNull Gson gson, final TypeToken<T> typeToken) {
        // Pick up the down stream type adapter to avoid infinite recursion
        final TypeAdapter<T> delegateAdapter = gson.getDelegateAdapter(this, typeToken);
        // Collect @SerializedPath annotated fields
        final Collection<FieldInfo> fieldInfos = FieldInfo.of(typeToken.getRawType());
        // If no such fields found, then just return the delegated type adapter
        // Otherwise wrap the type adapter in order to make some annotation processing
        return fieldInfos.isEmpty()
            ? delegateAdapter
            : new JsonPathTypeAdapter<>(gson, delegateAdapter, gson.getAdapter(JsonElement.class), fieldInfos);
    }

    @Getter
    @RequiredArgsConstructor
    private static class JsonPathTypeAdapter<T> extends TypeAdapter<T> {

        private final Gson gson;
        private final TypeAdapter<T> delegateAdapter;
        private final TypeAdapter<JsonElement> jsonElementTypeAdapter;
        private final Collection<FieldInfo> fieldInfos;


        @Override
        public void write(final JsonWriter out, final T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            // Serialize with the delegate to get a flat JSON tree
            JsonElement jsonTree = this.getDelegateAdapter().toJsonTree(value);

            if (jsonTree.isJsonObject()) {
                JsonObject jsonObject = jsonTree.getAsJsonObject();

                for (FieldInfo fieldInfo : this.getFieldInfos()) {
                    String flatKey = fieldInfo.getSerializedName();

                    if (jsonObject.has(flatKey)) {
                        JsonElement fieldValue = jsonObject.remove(flatKey);

                        // Build the nested path structure
                        ConcurrentList<String> pathList = fieldInfo.getJsonPathList();
                        JsonObject current = jsonObject;

                        for (int i = 0; i < pathList.size() - 1; i++) {
                            String segment = pathList.get(i);

                            if (current.has(segment) && current.get(segment).isJsonObject())
                                current = current.getAsJsonObject(segment);
                            else {
                                JsonObject nested = new JsonObject();
                                current.add(segment, nested);
                                current = nested;
                            }
                        }

                        current.add(pathList.getLast(), fieldValue);
                    }
                }

                this.getJsonElementTypeAdapter().write(out, jsonObject);
            } else
                this.getDelegateAdapter().write(out, value);
        }

        @Override
        public T read(final JsonReader in) throws IOException {
            // Building the original JSON tree to keep *all* fields
            final JsonElement outerJsonElement = this.getJsonElementTypeAdapter().read(in).getAsJsonObject();
            // Deserialize the value, not-existing fields will be omitted
            final T value = this.getDelegateAdapter().fromJsonTree(outerJsonElement);

            for (FieldInfo fieldInfo : this.getFieldInfos()) {
                try {
                    JsonElement innerJsonElement = outerJsonElement;
                    boolean skip = false;

                    // Resolve the JSON element by a JSON path expression
                    for (String pathNode : fieldInfo.getJsonPathList()) {
                        JsonObject innerJsonObject = innerJsonElement.getAsJsonObject();

                        if (innerJsonObject.has(pathNode))
                            innerJsonElement = innerJsonObject.get(pathNode);
                        else {
                            skip = true;
                            break;
                        }

                        // Ignore empty objects/arrays
                        if (innerJsonElement.isJsonObject() && innerJsonElement.getAsJsonObject().isEmpty())
                            skip = true;
                        else if (innerJsonObject.isJsonArray() && innerJsonElement.getAsJsonArray().isEmpty())
                            skip = true;
                    }

                    if (skip)
                        continue;

                    // Convert it to the field type (getGenericType preserves parameterized types like Optional<Integer>)
                    final Object innerValue = this.getGson().fromJson(innerJsonElement, fieldInfo.getAccessor().getGenericType());

                    // Now it can be assigned to the object field...
                    fieldInfo.getAccessor().set(value, innerValue);
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
            }

            return value;
        }

    }

    @Getter
    private static final class FieldInfo {

        private final FieldAccessor<?> accessor;
        private final String jsonPath;
        private final String serializedName;
        private final ConcurrentList<String> jsonPathList;

        private FieldInfo(@NotNull FieldAccessor<?> accessor, @NotNull String jsonPath) {
            this.accessor = accessor;
            this.jsonPath = jsonPath;
            this.jsonPathList = Concurrent.newList(StringUtil.split(jsonPath, "."));
            this.serializedName = accessor.getAnnotation(SerializedName.class).map(SerializedName::value).orElse(accessor.getName());
        }

        private static @NotNull Collection<FieldInfo> of(@NotNull Class<?> clazz) {
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(false);
            Collection<FieldInfo> collection = new ArrayList<>();

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                accessor.getAnnotation(SerializedPath.class).ifPresent(sp ->
                    collection.add(new FieldInfo(accessor, sp.value()))
                );
            }

            return collection;
        }

    }

}
