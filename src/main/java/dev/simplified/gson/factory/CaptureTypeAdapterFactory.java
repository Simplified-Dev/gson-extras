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
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.Capture;
import dev.simplified.gson.SerializedPath;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
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
 * the factory enters class-value grouping mode - entries are auto-grouped by affix
 * matching against the value class's field serialized names, then each group is
 * deserialized as an instance of that class.
 * <p>
 * Affix direction is determined by the field's serialized name:
 * <ul>
 *     <li><b>Prefix</b> ({@code fieldName_baseName}) - detected by {@code ^} at the
 *         start or {@code _} at the end of the serialized name</li>
 *     <li><b>Suffix</b> ({@code baseName_fieldName}) - detected by {@code $} at the
 *         end or {@code _} at the start of the serialized name</li>
 *     <li><b>Auto suffix</b> (default) - plain names without markers are treated as
 *         suffixes with an underscore separator prepended automatically</li>
 *     <li><b>Bare field</b> - {@code @SerializedName("")} captures the base key
 *         itself with no affix</li>
 * </ul>
 * The {@code ^} and {@code $} markers are stripped for matching and serialization;
 * the {@code _} separator is preserved.
 *
 * @see Capture
 */
@NoArgsConstructor
public final class CaptureTypeAdapterFactory implements TypeAdapterFactory {

    private static final Map<Object, JsonObject> OVERFLOW = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Holds a match pattern and the corresponding {@link SerializedName @SerializedName}
     * key used inside group {@link JsonObject JsonObjects} for Gson deserialization.
     *
     * @param matchPattern the clean pattern for {@link String#startsWith} or
     *                     {@link String#endsWith} matching
     * @param serializedKey the key stored in the group {@link JsonObject} - must match
     *                      the field's {@link SerializedName @SerializedName} exactly
     *                      so Gson can deserialize it
     */
    private record GroupAffix(@NotNull String matchPattern, @NotNull String serializedKey) {}

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

                // Target object: for descend fields, entries go into a nested object;
                // for normal captures, entries go directly into the root object
                JsonObject target = captureInfo.isDescend() ? new JsonObject() : jsonObject;

                if (captureInfo.isGroupingMode()) {
                    // Flatten grouped entries back
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String groupKey = serializeMapKey(entry.getKey(), captureInfo);
                        JsonElement groupElement = this.getGson().toJsonTree(entry.getValue());

                        if (groupElement.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> field : groupElement.getAsJsonObject().entrySet()) {
                                String fieldKey = field.getKey();
                                String reconstructed;

                                if (fieldKey.isEmpty()) {
                                    // Bare field
                                    reconstructed = groupKey;
                                } else if (fieldKey.startsWith("^")) {
                                    // Explicit prefix marker
                                    reconstructed = fieldKey.substring(1) + groupKey;
                                } else if (fieldKey.endsWith("$")) {
                                    // Explicit suffix marker
                                    reconstructed = groupKey + fieldKey.substring(0, fieldKey.length() - 1);
                                } else if (fieldKey.endsWith("_")) {
                                    // Prefix via trailing underscore
                                    reconstructed = fieldKey + groupKey;
                                } else if (fieldKey.startsWith("_")) {
                                    // Suffix via leading underscore
                                    reconstructed = groupKey + fieldKey;
                                } else {
                                    // Auto suffix
                                    reconstructed = groupKey + "_" + fieldKey;
                                }

                                String originalKey = captureInfo.hasFilter()
                                    ? captureInfo.getLiteralPrefix() + reconstructed
                                    : reconstructed;
                                target.add(originalKey, field.getValue());
                            }
                        }
                    }
                } else {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String strippedKey = serializeMapKey(entry.getKey(), captureInfo);
                        String originalKey = captureInfo.hasFilter()
                            ? captureInfo.getLiteralPrefix() + strippedKey
                            : strippedKey;
                        target.add(originalKey, this.getGson().toJsonTree(entry.getValue()));
                    }
                }

                if (captureInfo.isDescend())
                    jsonObject.add(captureInfo.getSerializedName(), target);

                // Merge overflow back
                JsonObject overflow = OVERFLOW.get(mapObj);

                if (overflow != null) {
                    JsonObject overflowTarget = captureInfo.isDescend()
                        ? jsonObject.getAsJsonObject(captureInfo.getSerializedName())
                        : jsonObject;

                    if (overflowTarget != null) {
                        for (Map.Entry<String, JsonElement> entry : overflow.entrySet())
                            overflowTarget.add(entry.getKey(), entry.getValue());
                    }
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

            // Pre-process descend fields: extract nested objects and capture their entries
            for (CaptureFieldInfo info : this.getCaptureFields()) {
                if (!info.isDescend())
                    continue;

                JsonElement nested = rootObject.remove(info.getSerializedName());

                if (nested == null || !nested.isJsonObject())
                    continue;

                for (Map.Entry<String, JsonElement> entry : nested.getAsJsonObject().entrySet()) {
                    String key = entry.getKey();
                    JsonElement value = entry.getValue();

                    if (info.hasFilter()) {
                        if (!info.getPattern().matcher(key).find())
                            continue;

                        String strippedKey = key.replaceFirst(info.getFilter(), "");

                        if (info.isGroupingMode())
                            capturedJsonMaps.get(info.getFieldName()).add(strippedKey, value);
                        else if (isCompatibleCaptureEntry(strippedKey, value, info))
                            capturedJsonMaps.get(info.getFieldName()).add(strippedKey, value);
                        else
                            overflowMaps.get(info.getFieldName()).add(key, value);
                    } else {
                        if (info.isGroupingMode() || isCompatibleCaptureEntry(key, value, info))
                            capturedJsonMaps.get(info.getFieldName()).add(key, value);
                        else
                            overflowMaps.get(info.getFieldName()).add(key, value);
                    }
                }
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
                Map<Object, Object> capturedMap;

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

        private @NotNull Map<Object, Object> buildSimpleMap(@NotNull JsonObject json, @NotNull CaptureFieldInfo info) {
            Map<Object, Object> map = info.newMapInstance();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    Object key = this.getGson().fromJson(new JsonPrimitive(entry.getKey()), info.getKeyType());
                    Object value = this.getGson().fromJson(entry.getValue(), info.getValueType());
                    map.put(key, value);
                } catch (Exception ex) {
                }
            }

            return map;
        }

        private @NotNull Map<Object, Object> buildGroupedMap(@NotNull JsonObject json, @NotNull CaptureFieldInfo info) {
            Map<Object, Object> map = info.newMapInstance();

            // Group entries by matching suffixes against value class field names
            ConcurrentMap<String, JsonObject> groups = Concurrent.newMap();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String strippedKey = entry.getKey();
                boolean matched = false;

                // Try longest suffix first
                for (GroupAffix suffix : info.getGroupSuffixes()) {
                    if (strippedKey.endsWith(suffix.matchPattern())) {
                        String groupKey = strippedKey.substring(0, strippedKey.length() - suffix.matchPattern().length());

                        if (!groups.containsKey(groupKey))
                            groups.put(groupKey, new JsonObject());

                        groups.get(groupKey).add(suffix.serializedKey(), entry.getValue());
                        matched = true;
                        break;
                    }
                }

                // Try longest prefix next
                if (!matched) {
                    for (GroupAffix prefix : info.getGroupPrefixes()) {
                        if (strippedKey.startsWith(prefix.matchPattern())) {
                            String groupKey = strippedKey.substring(prefix.matchPattern().length());

                            if (!groups.containsKey(groupKey))
                                groups.put(groupKey, new JsonObject());

                            groups.get(groupKey).add(prefix.serializedKey(), entry.getValue());
                            matched = true;
                            break;
                        }
                    }
                }

                if (!matched) {
                    if (info.hasBareField()) {
                        String groupKey = strippedKey;

                        if (!groups.containsKey(groupKey))
                            groups.put(groupKey, new JsonObject());

                        groups.get(groupKey).add("", entry.getValue());
                    }
                }
            }

            // Deserialize each group as value type
            for (Map.Entry<String, JsonObject> group : groups.entrySet()) {
                try {
                    Object key = this.getGson().fromJson(new JsonPrimitive(group.getKey()), info.getKeyType());
                    Object value = this.getGson().fromJson(group.getValue(), info.getValueType());
                    map.put(key, value);
                } catch (Exception ex) {
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
        private final @NotNull Class<? extends Map> mapImplClass;
        private final boolean groupingMode;
        private final @NotNull ConcurrentList<GroupAffix> groupSuffixes;
        private final @NotNull ConcurrentList<GroupAffix> groupPrefixes;
        private final boolean bareField;
        private final boolean descend;

        private CaptureFieldInfo(@NotNull FieldAccessor<?> accessor) {
            this.accessor = accessor;
            this.fieldName = accessor.getName();
            this.serializedName = accessor.getAnnotation(SerializedName.class)
                .map(SerializedName::value)
                .orElse(accessor.getName());
            this.filter = accessor.getAnnotation(Capture.class)
                .map(Capture::filter)
                .orElse("");
            this.descend = accessor.getAnnotation(Capture.class)
                .map(Capture::descend)
                .orElse(false);
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

            // Resolve the concrete Map implementation for reflective no-arg construction.
            // Bare interfaces or abstract classes fall back to ConcurrentMap for thread-safety.
            Class<?> rawFieldType = accessor.getFieldType();

            if (rawFieldType.isInterface() || Modifier.isAbstract(rawFieldType.getModifiers()))
                this.mapImplClass = ConcurrentMap.class;
            else {
                @SuppressWarnings("unchecked")
                Class<? extends Map> concrete = (Class<? extends Map>) rawFieldType;
                this.mapImplClass = concrete;
            }

            // Determine if grouping mode (value is a class with fields, not primitive/String/enum/Map/Collection)
            Class<?> rawValueType = getRawType(this.valueType);
            this.groupingMode = !rawValueType.isPrimitive()
                && !Number.class.isAssignableFrom(rawValueType)
                && rawValueType != String.class
                && rawValueType != Boolean.class
                && !rawValueType.isEnum()
                && rawValueType != Object.class
                && !Map.class.isAssignableFrom(rawValueType)
                && !java.util.Collection.class.isAssignableFrom(rawValueType);

            if (this.groupingMode) {
                this.groupSuffixes = Concurrent.newList();
                this.groupPrefixes = Concurrent.newList();
                this.bareField = discoverGroupAffixes(rawValueType, this.groupSuffixes, this.groupPrefixes);
            } else {
                this.groupSuffixes = Concurrent.newList();
                this.groupPrefixes = Concurrent.newList();
                this.bareField = false;
            }
        }

        boolean hasFilter() {
            return !this.filter.isEmpty();
        }

        boolean hasBareField() {
            return this.bareField;
        }

        @NotNull Map<Object, Object> newMapInstance() {
            try {
                @SuppressWarnings("unchecked")
                Map<Object, Object> instance = (Map<Object, Object>) this.mapImplClass.getDeclaredConstructor().newInstance();
                return instance;
            } catch (ReflectiveOperationException ex) {
                // Concrete type lacks an accessible no-arg constructor - degrade to ConcurrentMap
                return Concurrent.newMap();
            }
        }

        private static boolean discoverGroupAffixes(@NotNull Class<?> clazz, @NotNull ConcurrentList<GroupAffix> suffixes, @NotNull ConcurrentList<GroupAffix> prefixes) {
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
                } else if (name.startsWith("^")) {
                    prefixes.add(new GroupAffix(name.substring(1), name));
                } else if (name.endsWith("$")) {
                    suffixes.add(new GroupAffix(name.substring(0, name.length() - 1), name));
                } else if (name.endsWith("_")) {
                    prefixes.add(new GroupAffix(name, name));
                } else if (name.startsWith("_")) {
                    suffixes.add(new GroupAffix(name, name));
                } else {
                    suffixes.add(new GroupAffix("_" + name, name));
                }
            }

            // Sort longest match pattern first for greedy matching
            suffixes.sort((a, b) -> Integer.compare(b.matchPattern().length(), a.matchPattern().length()));
            prefixes.sort((a, b) -> Integer.compare(b.matchPattern().length(), a.matchPattern().length()));
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
