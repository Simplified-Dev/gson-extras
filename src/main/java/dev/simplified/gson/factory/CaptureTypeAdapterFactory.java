package dev.simplified.gson.factory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.simplified.collection.concurrent.Concurrent;
import dev.simplified.collection.concurrent.ConcurrentList;
import dev.simplified.collection.concurrent.ConcurrentMap;
import dev.simplified.gson.Capture;
import dev.simplified.gson.SerializedPath;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Gson {@link TypeAdapterFactory} that processes {@link Capture @Capture} annotations
 * on Map fields, capturing dynamic JSON entries into typed maps.
 * <p>
 * Entries whose keys match a filtered {@code @Capture} field's regex have the matched
 * portion stripped. A catch-all (empty filter) collects remaining unmatched entries.
 * Incompatible entries are stored as overflow for round-trip fidelity.
 * <p>
 * When a map's value type is a class with fields (not a primitive, String, or enum),
 * the factory enters class-value grouping mode - entries are auto-grouped by suffix
 * matching against the value class's field serialized names, then each group is
 * deserialized as an instance of that class.
 *
 * @see Capture
 */
@Log4j2
@NoArgsConstructor
public final class CaptureTypeAdapterFactory implements TypeAdapterFactory {

    private static final Map<Object, JsonObject> OVERFLOW = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> typeToken) {
        ConcurrentList<CaptureFieldInfo> captureFields = CaptureFieldInfo.of(typeToken.getRawType());

        if (captureFields.isEmpty())
            return null;

        ConcurrentList<String> knownKeys = discoverKnownKeys(typeToken.getRawType(), captureFields);
        TypeAdapter<T> delegateAdapter = gson.getDelegateAdapter(this, typeToken);

        return new CaptureTypeAdapter<>(gson, delegateAdapter, gson.getAdapter(JsonElement.class), captureFields, knownKeys);
    }

    private static @NotNull ConcurrentList<String> discoverKnownKeys(@NotNull Class<?> clazz, @NotNull ConcurrentList<CaptureFieldInfo> captureFields) {
        ConcurrentList<String> keys = Concurrent.newList();
        Reflection<?> reflection = new Reflection<>(clazz);
        reflection.setProcessingSuperclass(false);

        for (FieldAccessor<?> accessor : reflection.getFields()) {
            if (Modifier.isTransient(accessor.getModifiers()))
                continue;

            if (accessor.hasAnnotation(Capture.class))
                continue;

            // @SerializedPath: add first path segment
            if (accessor.hasAnnotation(SerializedPath.class)) {
                String path = accessor.getAnnotation(SerializedPath.class).get().value();
                int dot = path.indexOf('.');

                if (dot > 0)
                    keys.add(path.substring(0, dot));
                else
                    keys.add(path);

                continue;
            }

            // @SerializedName: add value and alternates
            if (accessor.hasAnnotation(SerializedName.class)) {
                SerializedName sn = accessor.getAnnotation(SerializedName.class).get();
                keys.add(sn.value());

                for (String alt : sn.alternate())
                    keys.add(alt);
            } else
                keys.add(accessor.getName());
        }

        return keys;
    }

    @Getter
    @RequiredArgsConstructor
    private static class CaptureTypeAdapter<T> extends TypeAdapter<T> {

        private final @NotNull Gson gson;
        private final @NotNull TypeAdapter<T> delegateAdapter;
        private final @NotNull TypeAdapter<JsonElement> jsonElementAdapter;
        private final @NotNull ConcurrentList<CaptureFieldInfo> captureFields;
        private final @NotNull ConcurrentList<String> knownKeys;

        @Override
        public void write(@NotNull JsonWriter out, @Nullable T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            JsonElement jsonTree = this.getDelegateAdapter().toJsonTree(value);

            if (!jsonTree.isJsonObject()) {
                this.getDelegateAdapter().write(out, value);
                return;
            }

            JsonObject jsonObject = jsonTree.getAsJsonObject();

            for (CaptureFieldInfo captureInfo : this.getCaptureFields()) {
                Object mapObj = captureInfo.getAccessor().get(value);

                if (!(mapObj instanceof Map<?, ?> map))
                    continue;

                // Remove the capture field's own serialized key from the output
                jsonObject.remove(captureInfo.getSerializedName());

                if (captureInfo.isGroupingMode()) {
                    // Flatten grouped entries back
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String groupKey = serializeMapKey(entry.getKey(), captureInfo);
                        JsonElement groupElement = this.getGson().toJsonTree(entry.getValue());

                        if (groupElement.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> field : groupElement.getAsJsonObject().entrySet()) {
                                String originalKey;

                                if (field.getKey().isEmpty()) {
                                    // Bare field: no suffix
                                    originalKey = captureInfo.hasFilter()
                                        ? captureInfo.getLiteralPrefix() + groupKey
                                        : groupKey;
                                } else {
                                    originalKey = captureInfo.hasFilter()
                                        ? captureInfo.getLiteralPrefix() + groupKey + "_" + field.getKey()
                                        : groupKey + "_" + field.getKey();
                                }

                                jsonObject.add(originalKey, field.getValue());
                            }
                        }
                    }
                } else {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String strippedKey = serializeMapKey(entry.getKey(), captureInfo);
                        String originalKey = captureInfo.hasFilter()
                            ? captureInfo.getLiteralPrefix() + strippedKey
                            : strippedKey;
                        jsonObject.add(originalKey, this.getGson().toJsonTree(entry.getValue()));
                    }
                }

                // Merge overflow back
                JsonObject overflow = OVERFLOW.get(mapObj);

                if (overflow != null) {
                    for (Map.Entry<String, JsonElement> entry : overflow.entrySet())
                        jsonObject.add(entry.getKey(), entry.getValue());
                }
            }

            this.getJsonElementAdapter().write(out, jsonObject);
        }

        @Override
        public @Nullable T read(@NotNull JsonReader in) throws IOException {
            JsonElement rootElement = this.getJsonElementAdapter().read(in);

            if (!rootElement.isJsonObject())
                return this.getDelegateAdapter().fromJsonTree(rootElement);

            JsonObject rootObject = rootElement.getAsJsonObject();
            JsonObject knownObject = new JsonObject();

            // Per-field captured entries and overflow
            ConcurrentMap<String, JsonObject> capturedJsonMaps = Concurrent.newMap();
            ConcurrentMap<String, JsonObject> overflowMaps = Concurrent.newMap();

            for (CaptureFieldInfo info : this.getCaptureFields()) {
                capturedJsonMaps.put(info.getFieldName(), new JsonObject());
                overflowMaps.put(info.getFieldName(), new JsonObject());
            }

            // Classify each JSON entry
            for (Map.Entry<String, JsonElement> entry : rootObject.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();

                if (this.getKnownKeys().contains(key)) {
                    knownObject.add(key, value);
                    continue;
                }

                boolean captured = false;

                // Try filtered captures first
                for (CaptureFieldInfo info : this.getCaptureFields()) {
                    if (!info.hasFilter())
                        continue;

                    if (!info.getPattern().matcher(key).find())
                        continue;

                    String strippedKey = key.replaceFirst(info.getFilter(), "");

                    if (info.isGroupingMode()) {
                        // In grouping mode, just store raw entries - grouping happens after
                        capturedJsonMaps.get(info.getFieldName()).add(strippedKey, value);
                    } else if (isCompatibleCaptureEntry(strippedKey, value, info)) {
                        capturedJsonMaps.get(info.getFieldName()).add(strippedKey, value);
                    } else {
                        overflowMaps.get(info.getFieldName()).add(key, value);
                    }

                    captured = true;
                    break;
                }

                if (captured)
                    continue;

                // Try catch-all capture
                CaptureFieldInfo catchAll = this.getCaptureFields().stream()
                    .filter(info -> !info.hasFilter())
                    .findFirst()
                    .orElse(null);

                if (catchAll != null) {
                    if (catchAll.isGroupingMode() || isCompatibleCaptureEntry(key, value, catchAll))
                        capturedJsonMaps.get(catchAll.getFieldName()).add(key, value);
                    else
                        overflowMaps.get(catchAll.getFieldName()).add(key, value);
                } else {
                    // No match - add to known so delegate sees it
                    knownObject.add(key, value);
                }
            }

            // Delegate deserialization with known-only JSON
            T result = this.getDelegateAdapter().fromJsonTree(knownObject);

            if (result == null)
                return null;

            // Post-assign captured maps
            for (CaptureFieldInfo info : this.getCaptureFields()) {
                JsonObject capturedJson = capturedJsonMaps.get(info.getFieldName());
                ConcurrentMap<Object, Object> capturedMap;

                if (info.isGroupingMode())
                    capturedMap = buildGroupedMap(capturedJson, info);
                else
                    capturedMap = buildSimpleMap(capturedJson, info);

                info.getAccessor().set(result, capturedMap);

                // Store overflow
                JsonObject overflow = overflowMaps.get(info.getFieldName());

                if (overflow.size() > 0)
                    OVERFLOW.put(capturedMap, overflow);
            }

            return result;
        }

        private @NotNull ConcurrentMap<Object, Object> buildSimpleMap(@NotNull JsonObject json, @NotNull CaptureFieldInfo info) {
            ConcurrentMap<Object, Object> map = Concurrent.newMap();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    Object key = this.getGson().fromJson(new JsonPrimitive(entry.getKey()), info.getKeyType());
                    Object value = this.getGson().fromJson(entry.getValue(), info.getValueType());
                    map.put(key, value);
                } catch (Exception ex) {
                    log.warn("Failed to deserialize @Capture entry '{}': {}", entry.getKey(), ex.getMessage());
                }
            }

            return map;
        }

        private @NotNull ConcurrentMap<Object, Object> buildGroupedMap(@NotNull JsonObject json, @NotNull CaptureFieldInfo info) {
            ConcurrentMap<Object, Object> map = Concurrent.newMap();

            // Group entries by matching suffixes against value class field names
            ConcurrentMap<String, JsonObject> groups = Concurrent.newMap();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String strippedKey = entry.getKey();
                boolean matched = false;

                // Try longest suffix first
                for (String suffix : info.getGroupSuffixes()) {
                    if (strippedKey.endsWith(suffix)) {
                        String groupKey = strippedKey.substring(0, strippedKey.length() - suffix.length());
                        String fieldName = suffix.substring(1); // Remove leading underscore

                        if (!groups.containsKey(groupKey))
                            groups.put(groupKey, new JsonObject());

                        groups.get(groupKey).add(fieldName, entry.getValue());
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    if (info.hasBareField()) {
                        String groupKey = strippedKey;

                        if (!groups.containsKey(groupKey))
                            groups.put(groupKey, new JsonObject());

                        groups.get(groupKey).add("", entry.getValue());
                    } else
                        log.warn("@Capture grouping: no suffix match for '{}'", strippedKey);
                }
            }

            // Deserialize each group as value type
            for (Map.Entry<String, JsonObject> group : groups.entrySet()) {
                try {
                    Object key = this.getGson().fromJson(new JsonPrimitive(group.getKey()), info.getKeyType());
                    Object value = this.getGson().fromJson(group.getValue(), info.getValueType());
                    map.put(key, value);
                } catch (Exception ex) {
                    log.warn("Failed to deserialize @Capture group '{}': {}", group.getKey(), ex.getMessage());
                }
            }

            return map;
        }

        private boolean isCompatibleCaptureEntry(@NotNull String key, @NotNull JsonElement value, @NotNull CaptureFieldInfo info) {
            try {
                // Check key compatibility
                Class<?> rawKeyType = getRawType(info.getKeyType());

                if (rawKeyType != String.class) {
                    try {
                        this.getGson().fromJson(new JsonPrimitive(key), info.getKeyType());
                    } catch (Exception ex) {
                        return false;
                    }
                }

                // Check value compatibility
                return isCompatibleValue(value, info.getValueType());
            } catch (Exception ignored) {
                return false;
            }
        }

        private boolean isCompatibleValue(@NotNull JsonElement element, @NotNull Type expectedType) {
            try {
                Class<?> rawType = getRawType(expectedType);

                if (element.isJsonNull())
                    return !rawType.isPrimitive();

                if (element.isJsonPrimitive()) {
                    JsonPrimitive primitive = element.getAsJsonPrimitive();

                    if (rawType == String.class)
                        return primitive.isString();

                    if (rawType == Boolean.class || rawType == boolean.class)
                        return primitive.isBoolean();

                    if (Number.class.isAssignableFrom(rawType) || rawType.isPrimitive()) {
                        if (!primitive.isNumber())
                            return false;

                        if (rawType == Integer.class || rawType == int.class) {
                            double d = primitive.getAsDouble();
                            return d == Math.floor(d) && !Double.isInfinite(d)
                                && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
                        }

                        if (rawType == Long.class || rawType == long.class) {
                            double d = primitive.getAsDouble();
                            return d == Math.floor(d) && !Double.isInfinite(d);
                        }

                        return true;
                    }

                    if (rawType.isEnum()) {
                        Object result = this.getGson().fromJson(element, expectedType);
                        return result != null;
                    }
                }

                return element.isJsonObject() && !rawType.isPrimitive()
                    && !Number.class.isAssignableFrom(rawType)
                    && rawType != String.class && rawType != Boolean.class;
            } catch (Exception ignored) {
                return false;
            }
        }

        private @NotNull String serializeMapKey(@Nullable Object key, @NotNull CaptureFieldInfo info) {
            if (key == null)
                return "null";

            if (key instanceof String s)
                return s;

            if (key.getClass().isEnum())
                return serializeEnumKey((Enum<?>) key);

            return key.toString();
        }

        private @NotNull String serializeEnumKey(@NotNull Enum<?> enumValue) {
            try {
                SerializedName sn = enumValue.getClass().getField(enumValue.name()).getAnnotation(SerializedName.class);

                if (sn != null)
                    return sn.value();
            } catch (NoSuchFieldException ignored) { }

            return enumValue.name();
        }

        private static @NotNull Class<?> getRawType(@NotNull Type type) {
            if (type instanceof Class<?> clazz)
                return clazz;

            if (type instanceof ParameterizedType parameterized)
                return (Class<?>) parameterized.getRawType();

            return Object.class;
        }

    }

    @Getter
    private static final class CaptureFieldInfo {

        private final @NotNull FieldAccessor<?> accessor;
        private final @NotNull String fieldName;
        private final @NotNull String serializedName;
        private final @NotNull String filter;
        private final @Nullable Pattern pattern;
        private final @NotNull String literalPrefix;
        private final @NotNull Type keyType;
        private final @NotNull Type valueType;
        private final boolean groupingMode;
        private final @NotNull ConcurrentList<String> groupSuffixes;
        private final boolean bareField;

        private CaptureFieldInfo(@NotNull FieldAccessor<?> accessor) {
            this.accessor = accessor;
            this.fieldName = accessor.getName();
            this.serializedName = accessor.getAnnotation(SerializedName.class)
                .map(SerializedName::value)
                .orElse(accessor.getName());
            this.filter = accessor.getAnnotation(Capture.class)
                .map(Capture::filter)
                .orElse("");
            this.pattern = hasFilter() ? Pattern.compile(this.filter) : null;
            this.literalPrefix = hasFilter() ? this.filter.replaceAll("^\\^", "").replaceAll("\\$$", "") : "";

            Type genericType = accessor.getGenericType();

            if (genericType instanceof ParameterizedType parameterized) {
                Type[] typeArgs = parameterized.getActualTypeArguments();
                this.keyType = typeArgs.length >= 1 ? typeArgs[0] : Object.class;
                this.valueType = typeArgs.length >= 2 ? typeArgs[1] : Object.class;
            } else {
                this.keyType = Object.class;
                this.valueType = Object.class;
            }

            // Determine if grouping mode (value is a class with fields, not primitive/String/enum)
            Class<?> rawValueType = getRawType(this.valueType);
            this.groupingMode = !rawValueType.isPrimitive()
                && !Number.class.isAssignableFrom(rawValueType)
                && rawValueType != String.class
                && rawValueType != Boolean.class
                && !rawValueType.isEnum()
                && rawValueType != Object.class;

            if (this.groupingMode) {
                this.groupSuffixes = Concurrent.newList();
                this.bareField = discoverGroupSuffixes(rawValueType, this.groupSuffixes);
            } else {
                this.groupSuffixes = Concurrent.newList();
                this.bareField = false;
            }
        }

        boolean hasFilter() {
            return !this.filter.isEmpty();
        }

        boolean hasBareField() {
            return this.bareField;
        }

        private static boolean discoverGroupSuffixes(@NotNull Class<?> clazz, @NotNull ConcurrentList<String> suffixes) {
            boolean hasBare = false;
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(false);

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                if (Modifier.isTransient(accessor.getModifiers()))
                    continue;

                String name = accessor.getAnnotation(SerializedName.class)
                    .map(SerializedName::value)
                    .orElse(accessor.getName());

                if (name.isEmpty()) {
                    hasBare = true;
                    continue;
                }

                suffixes.add("_" + name);
            }

            // Sort longest first for greedy matching
            suffixes.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return hasBare;
        }

        private static @NotNull Class<?> getRawType(@NotNull Type type) {
            if (type instanceof Class<?> clazz)
                return clazz;

            if (type instanceof ParameterizedType parameterized)
                return (Class<?>) parameterized.getRawType();

            return Object.class;
        }

        private static @NotNull ConcurrentList<CaptureFieldInfo> of(@NotNull Class<?> clazz) {
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(false);
            ConcurrentList<CaptureFieldInfo> result = Concurrent.newList();

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                if (Modifier.isTransient(accessor.getModifiers()))
                    continue;

                if (!accessor.hasAnnotation(Capture.class))
                    continue;

                Type genericType = accessor.getGenericType();

                if (!(genericType instanceof ParameterizedType))
                    continue;

                if (!Map.class.isAssignableFrom(accessor.getFieldType()))
                    continue;

                result.add(new CaptureFieldInfo(accessor));
            }

            return result;
        }

    }

}
