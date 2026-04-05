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
import dev.simplified.gson.Split;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import dev.simplified.collection.tuple.pair.Pair;
import dev.simplified.collection.tuple.pair.PairOptional;
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
import java.util.regex.Pattern;

/**
 * Gson {@link TypeAdapterFactory} that processes {@link Split @Split} annotations
 * on {@link Pair} and {@link PairOptional} fields, splitting a single JSON string
 * value by a literal delimiter into the two generic type components.
 *
 * @see Split
 */
@Log4j2
@NoArgsConstructor
public final class SplitTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> typeToken) {
        ConcurrentList<SplitFieldInfo> splitFields = SplitFieldInfo.of(typeToken.getRawType());

        if (splitFields.isEmpty())
            return null;

        TypeAdapter<T> delegateAdapter = gson.getDelegateAdapter(this, typeToken);

        return new SplitTypeAdapter<>(gson, delegateAdapter, gson.getAdapter(JsonElement.class), splitFields);
    }

    @Getter
    @RequiredArgsConstructor
    private static class SplitTypeAdapter<T> extends TypeAdapter<T> {

        private final @NotNull Gson gson;
        private final @NotNull TypeAdapter<T> delegateAdapter;
        private final @NotNull TypeAdapter<JsonElement> jsonElementAdapter;
        private final @NotNull ConcurrentList<SplitFieldInfo> splitFields;

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

            for (SplitFieldInfo info : this.getSplitFields()) {
                // Remove whatever the delegate serialized for this field
                jsonObject.remove(info.getSerializedName());

                Object fieldValue = info.getAccessor().get(value);

                if (fieldValue == null) {
                    jsonObject.add(info.getSerializedName(), null);
                    continue;
                }

                if (info.isPairOptional()) {
                    PairOptional<?, ?> pairOpt = (PairOptional<?, ?>) fieldValue;

                    if (pairOpt.isEmpty()) {
                        jsonObject.add(info.getSerializedName(), null);
                        continue;
                    }

                    String joined = serializePart(pairOpt.getLeft(), info.getLeftType())
                        + info.getDelimiter()
                        + serializePart(pairOpt.getRight(), info.getRightType());
                    jsonObject.add(info.getSerializedName(), new JsonPrimitive(joined));
                } else {
                    Pair<?, ?> pair = (Pair<?, ?>) fieldValue;

                    if (pair.isEmpty()) {
                        jsonObject.add(info.getSerializedName(), null);
                        continue;
                    }

                    String joined = serializePart(pair.getLeft(), info.getLeftType())
                        + info.getDelimiter()
                        + serializePart(pair.getRight(), info.getRightType());
                    jsonObject.add(info.getSerializedName(), new JsonPrimitive(joined));
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

            // Extract @Split values before delegating
            ConcurrentList<SplitClaim> claims = Concurrent.newList();

            for (SplitFieldInfo info : this.getSplitFields()) {
                JsonElement element = rootObject.remove(info.getSerializedName());

                if (element != null && !element.isJsonNull() && element.isJsonPrimitive())
                    claims.add(new SplitClaim(info, element.getAsString()));
            }

            // Delegate deserialization of remaining JSON
            T result = this.getDelegateAdapter().fromJsonTree(rootObject);

            if (result == null)
                return null;

            // Post-assign split values
            for (SplitClaim claim : claims) {
                try {
                    String[] parts = claim.value().split(Pattern.quote(claim.info().getDelimiter()), 2);

                    if (parts.length != 2) {
                        log.warn("@Split('{}') on field '{}': expected 2 parts but got {}",
                            claim.info().getDelimiter(), claim.info().getAccessor().getName(), parts.length);
                        continue;
                    }

                    Object left = this.getGson().fromJson(new JsonPrimitive(parts[0]), claim.info().getLeftType());
                    Object right = this.getGson().fromJson(new JsonPrimitive(parts[1]), claim.info().getRightType());

                    if (claim.info().isPairOptional())
                        claim.info().getAccessor().set(result, PairOptional.of(left, right));
                    else
                        claim.info().getAccessor().set(result, Pair.of(left, right));
                } catch (Exception ex) {
                    log.warn("Failed to deserialize @Split field '{}': {}", claim.info().getAccessor().getName(), ex.getMessage());
                }
            }

            return result;
        }

        @SuppressWarnings("unchecked")
        private @NotNull String serializePart(@Nullable Object value, @NotNull Type type) {
            if (value == null)
                return "";

            TypeAdapter<Object> adapter = (TypeAdapter<Object>) this.getGson().getAdapter(TypeToken.get(type));
            JsonElement element = adapter.toJsonTree(value);

            if (element.isJsonPrimitive())
                return element.getAsString();

            return element.toString();
        }

    }

    private record SplitClaim(@NotNull SplitFieldInfo info, @NotNull String value) { }

    @Getter
    private static final class SplitFieldInfo {

        private final @NotNull FieldAccessor<?> accessor;
        private final @NotNull String serializedName;
        private final @NotNull String delimiter;
        private final @NotNull Type leftType;
        private final @NotNull Type rightType;
        private final boolean pairOptional;

        private SplitFieldInfo(@NotNull FieldAccessor<?> accessor) {
            this.accessor = accessor;
            this.serializedName = accessor.getAnnotation(SerializedName.class)
                .map(SerializedName::value)
                .orElse(accessor.getName());
            this.delimiter = accessor.getAnnotation(Split.class)
                .map(Split::value)
                .orElse("");
            this.pairOptional = PairOptional.class.isAssignableFrom(accessor.getFieldType());

            Type genericType = accessor.getGenericType();

            if (genericType instanceof ParameterizedType parameterized) {
                Type[] typeArgs = parameterized.getActualTypeArguments();
                this.leftType = typeArgs.length >= 1 ? typeArgs[0] : Object.class;
                this.rightType = typeArgs.length >= 2 ? typeArgs[1] : Object.class;
            } else {
                this.leftType = Object.class;
                this.rightType = Object.class;
            }
        }

        private static @NotNull ConcurrentList<SplitFieldInfo> of(@NotNull Class<?> clazz) {
            Reflection<?> reflection = new Reflection<>(clazz);
            reflection.setProcessingSuperclass(false);
            ConcurrentList<SplitFieldInfo> result = Concurrent.newList();

            for (FieldAccessor<?> accessor : reflection.getFields()) {
                if (Modifier.isTransient(accessor.getModifiers()))
                    continue;

                if (!accessor.hasAnnotation(Split.class))
                    continue;

                Class<?> rawType = accessor.getFieldType();

                if (rawType != Pair.class && rawType != PairOptional.class)
                    continue;

                result.add(new SplitFieldInfo(accessor));
            }

            return result;
        }

    }

}
