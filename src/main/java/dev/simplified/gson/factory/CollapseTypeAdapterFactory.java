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
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.Collapse;
import dev.simplified.gson.Key;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gson {@link TypeAdapterFactory} that processes {@link Collapse @Collapse} annotations
 * on {@link Map} or {@link List} fields, injecting JSON object keys into value objects
 * via an optional {@link Key @Key}-annotated field.
 * <p>
 * Supports two field shapes from the same JSON object source:
 * <ul>
 *     <li><b>{@code Map<K, V>}</b> - preserves the key-value association; each value's
 *         {@link Key @Key} field (if present) receives the entry key.</li>
 *     <li><b>{@code List<V>}</b> - collects values into a list; each element's
 *         {@link Key @Key} field (if present) receives the original object key.
 *         The factory internally tracks key order for round-trip fidelity.</li>
 * </ul>
 * The {@link Key @Key} annotation is optional - when absent, key injection is skipped
 * but serialization still works. For map-mode, keys come from the map entries. For
 * list-mode, keys are tracked internally via a side-channel.
 * <p>
 * Serialization reverses the process: for maps, the map key is used; for lists, the
 * internally tracked key order (or {@link Key @Key} field if present) reconstructs
 * the JSON object. The key field itself is excluded from the serialized value.
 *
 * @see Collapse
 * @see Key
 */
@NoArgsConstructor
public final class CollapseTypeAdapterFactory implements TypeAdapterFactory {

    private static final Map<Object, ConcurrentList<String>> KEY_ORDER = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> typeToken) {
        ConcurrentList<CollapseFieldInfo> collapseFields = CollapseFieldInfo.of(typeToken.getRawType());

        if (collapseFields.isEmpty())
            return null;

        TypeAdapter<T> delegateAdapter = gson.getDelegateAdapter(this, typeToken);

        return new CollapseTypeAdapter<>(gson, delegateAdapter, gson.getAdapter(JsonElement.class), collapseFields);
    }

    private static class CollapseTypeAdapter<T> extends TypeAdapter<T> {

        private final @NotNull Gson gson;
        private final @NotNull TypeAdapter<T> delegateAdapter;
        private final @NotNull TypeAdapter<JsonElement> jsonElementAdapter;
        private final @NotNull ConcurrentList<CollapseFieldInfo> collapseFields;

        private CollapseTypeAdapter(@NotNull Gson gson, @NotNull TypeAdapter<T> delegateAdapter, @NotNull TypeAdapter<JsonElement> jsonElementAdapter, @NotNull ConcurrentList<CollapseFieldInfo> collapseFields) {
            this.gson = gson;
            this.delegateAdapter = delegateAdapter;
            this.jsonElementAdapter = jsonElementAdapter;
            this.collapseFields = collapseFields;
        }

        @Override
        public void write(@NotNull JsonWriter out, @Nullable T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            JsonElement jsonTree = this.delegateAdapter.toJsonTree(value);

            if (!jsonTree.isJsonObject()) {
                this.delegateAdapter.write(out, value);
                return;
            }

            JsonObject jsonObject = jsonTree.getAsJsonObject();

            for (CollapseFieldInfo info : this.collapseFields) {
                String serializedName = info.getSerializedName();
                JsonElement fieldElement = jsonObject.get(serializedName);

                if (fieldElement == null)
                    continue;

                // Remove the delegate-serialized field (map or list JSON)
                jsonObject.remove(serializedName);

                // Rebuild as a JSON object keyed by map keys or tracked key order
                JsonObject collapsed = new JsonObject();
                Object fieldValue = info.getAccessor().get(value);

                if (fieldValue instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String entryKey = serializeKey(entry.getKey());
                        JsonElement entryValue = this.gson.toJsonTree(entry.getValue());
                        removeKeyField(entryValue, info);
                        collapsed.add(entryKey, entryValue);
                    }
                } else if (fieldValue instanceof Collection<?> list) {
                    ConcurrentList<String> keys = KEY_ORDER.get(fieldValue);

                    int i = 0;

                    for (Object element : list) {
                        String entryKey;

                        if (keys != null && i < keys.size())
                            entryKey = keys.get(i);
                        else if (info.hasKeyAccessor())
                            entryKey = readKeyFromValue(element, info);
                        else
                            entryKey = String.valueOf(i);

                        JsonElement entryValue = this.gson.toJsonTree(element);
                        removeKeyField(entryValue, info);
                        collapsed.add(entryKey, entryValue);
                        i++;
                    }
                }

                jsonObject.add(serializedName, collapsed);
            }

            this.jsonElementAdapter.write(out, jsonObject);
        }

        @Override
        public @Nullable T read(@NotNull JsonReader in) throws IOException {
            JsonElement rootElement = this.jsonElementAdapter.read(in);

            if (!rootElement.isJsonObject())
                return this.delegateAdapter.fromJsonTree(rootElement);

            JsonObject rootObject = rootElement.getAsJsonObject();

            // Save original JSON objects before modifying rootObject for list-mode conversion
            ConcurrentMap<String, JsonObject> originalObjects = Concurrent.newMap();

            for (CollapseFieldInfo info : this.collapseFields) {
                String serializedName = info.getSerializedName();
                JsonElement fieldElement = rootObject.get(serializedName);

                if (fieldElement == null || !fieldElement.isJsonObject())
                    continue;

                originalObjects.put(serializedName, fieldElement.getAsJsonObject());

                if (info.isListMode()) {
                    // Convert JSON object to array for delegate deserialization
                    com.google.gson.JsonArray array = new com.google.gson.JsonArray();

                    for (Map.Entry<String, JsonElement> entry : fieldElement.getAsJsonObject().entrySet())
                        array.add(entry.getValue());

                    rootObject.add(serializedName, array);
                }
            }

            // Delegate deserialization
            T result = this.delegateAdapter.fromJsonTree(rootObject);

            if (result == null)
                return null;

            // Inject keys into values and track key order for lists
            for (CollapseFieldInfo info : this.collapseFields) {
                String serializedName = info.getSerializedName();
                JsonObject sourceObject = originalObjects.get(serializedName);

                if (sourceObject == null)
                    continue;

                Object fieldValue = info.getAccessor().get(result);

                if (fieldValue instanceof Map<?, ?> map) {
                    if (info.hasKeyAccessor()) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getValue() != null)
                                injectKey(entry.getValue(), serializeKey(entry.getKey()), info);
                        }
                    }
                } else if (fieldValue instanceof List<?> list) {
                    // Collect keys in JSON object order
                    ConcurrentList<String> keys = Concurrent.newList();

                    for (Map.Entry<String, JsonElement> entry : sourceObject.entrySet())
                        keys.add(entry.getKey());

                    // Track key order for round-trip serialization
                    KEY_ORDER.put(fieldValue, keys);

                    // Inject keys if @Key field is present
                    if (info.hasKeyAccessor()) {
                        for (int i = 0; i < Math.min(keys.size(), list.size()); i++) {
                            if (list.get(i) != null)
                                injectKey(list.get(i), keys.get(i), info);
                        }
                    }
                }
            }

            return result;
        }

        private static void injectKey(@NotNull Object value, @NotNull String key, @NotNull CollapseFieldInfo info) {
            try {
                FieldAccessor<?> keyAccessor = info.getKeyAccessor();

                if (keyAccessor == null)
                    return;

                Class<?> keyFieldType = keyAccessor.getFieldType();

                if (keyFieldType == String.class)
                    keyAccessor.set(value, key);
                else if (keyFieldType.isEnum())
                    keyAccessor.set(value, parseEnum(keyFieldType, key));
                else if (keyFieldType == int.class || keyFieldType == Integer.class)
                    keyAccessor.set(value, Integer.parseInt(key));
                else if (keyFieldType == long.class || keyFieldType == Long.class)
                    keyAccessor.set(value, Long.parseLong(key));
            } catch (Exception ignored) {
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static @Nullable Object parseEnum(@NotNull Class<?> enumType, @NotNull String value) {
            try {
                return Enum.valueOf((Class<Enum>) enumType, value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                try {
                    return Enum.valueOf((Class<Enum>) enumType, value);
                } catch (IllegalArgumentException ignored2) {
                    return null;
                }
            }
        }

        private static @NotNull String readKeyFromValue(@NotNull Object value, @NotNull CollapseFieldInfo info) {
            try {
                FieldAccessor<?> keyAccessor = info.getKeyAccessor();

                if (keyAccessor == null)
                    return "";

                Object key = keyAccessor.get(value);
                return serializeKey(key);
            } catch (Exception ignored) {
                return "";
            }
        }

        private static void removeKeyField(@NotNull JsonElement element, @NotNull CollapseFieldInfo info) {
            if (element.isJsonObject() && info.getKeySerializedName() != null)
                element.getAsJsonObject().remove(info.getKeySerializedName());
        }

        private static @NotNull String serializeKey(@Nullable Object key) {
            if (key == null)
                return "null";

            if (key instanceof String s)
                return s;

            if (key.getClass().isEnum()) {
                try {
                    SerializedName sn = key.getClass().getField(((Enum<?>) key).name()).getAnnotation(SerializedName.class);

                    if (sn != null)
                        return sn.value();
                } catch (NoSuchFieldException ignored) {
                }

                return ((Enum<?>) key).name();
            }

            return key.toString();
        }

    }

    @Getter
    private static class CollapseFieldInfo {

        private final @NotNull FieldAccessor<?> accessor;
        private final @NotNull String serializedName;
        private final boolean listMode;
        private final @NotNull Type valueType;
        private final @Nullable FieldAccessor<?> keyAccessor;
        private final @Nullable String keySerializedName;

        private CollapseFieldInfo(@NotNull FieldAccessor<?> accessor) {
            this.accessor = accessor;
            this.serializedName = accessor.getAnnotation(SerializedName.class)
                .map(SerializedName::value)
                .orElse(accessor.getName());

            Class<?> rawType = accessor.getFieldType();
            this.listMode = List.class.isAssignableFrom(rawType) || Collection.class.isAssignableFrom(rawType);

            Type genericType = accessor.getGenericType();

            if (genericType instanceof ParameterizedType parameterized) {
                Type[] typeArgs = parameterized.getActualTypeArguments();

                if (this.listMode)
                    this.valueType = typeArgs.length >= 1 ? typeArgs[0] : Object.class;
                else
                    this.valueType = typeArgs.length >= 2 ? typeArgs[1] : Object.class;
            } else {
                this.valueType = Object.class;
            }

            Class<?> rawValueType = getRawType(this.valueType);
            this.keyAccessor = findKeyAccessor(rawValueType);
            this.keySerializedName = this.keyAccessor != null
                ? this.keyAccessor.getAnnotation(SerializedName.class)
                    .map(SerializedName::value)
                    .orElse(this.keyAccessor.getName())
                : null;
        }

        boolean hasKeyAccessor() {
            return this.keyAccessor != null;
        }

        private static @Nullable FieldAccessor<?> findKeyAccessor(@NotNull Class<?> clazz) {
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(true);

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                if (accessor.hasAnnotation(Key.class))
                    return accessor;
            }

            return null;
        }

        private static @NotNull Class<?> getRawType(@NotNull Type type) {
            if (type instanceof Class<?> clazz)
                return clazz;

            if (type instanceof ParameterizedType parameterized)
                return (Class<?>) parameterized.getRawType();

            return Object.class;
        }

        private static @NotNull ConcurrentList<CollapseFieldInfo> of(@NotNull Class<?> clazz) {
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(false);
            ConcurrentList<CollapseFieldInfo> result = Concurrent.newList();

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                if (Modifier.isTransient(accessor.getModifiers()))
                    continue;

                if (!accessor.hasAnnotation(Collapse.class))
                    continue;

                result.add(new CollapseFieldInfo(accessor));
            }

            return result;
        }

    }

}
