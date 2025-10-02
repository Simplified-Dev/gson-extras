package dev.sbs.api.io.gson;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import dev.sbs.api.builder.ClassBuilder;
import dev.sbs.api.collection.concurrent.Concurrent;
import dev.sbs.api.collection.concurrent.ConcurrentList;
import dev.sbs.api.collection.concurrent.ConcurrentMap;
import dev.sbs.api.io.gson.adapter.StringTypeAdapter;
import dev.sbs.api.util.StringUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public class GsonSettings {

    private final @NotNull Optional<String> dateFormat;
    private final @NotNull FormattingStyle style;
    private final boolean serializingNulls;
    private final @NotNull StringType stringType;
    private final @NotNull ConcurrentMap<Type, Object> typeAdapters;
    private final @NotNull ConcurrentList<TypeAdapterFactory> factories;

    public @NotNull Gson create() {
        GsonBuilder builder = new GsonBuilder();
        if (this.isSerializingNulls())
            builder.serializeNulls();

        builder.setFormattingStyle(this.getStyle());
        builder.setDateFormat(this.dateFormat.orElse(null));
        this.getFactories().forEach(builder::registerTypeAdapterFactory);
        this.getTypeAdapters().forEach(builder::registerTypeAdapter);
        builder.registerTypeAdapter(String.class, new StringTypeAdapter(this.getStringType()));
        return builder.create();
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static @NotNull Builder from(@NotNull GsonSettings gsonSettings) {
        return builder()
            .withDateFormat(gsonSettings.getDateFormat())
            .withStyle(gsonSettings.getStyle())
            .isSerializingNulls(gsonSettings.isSerializingNulls())
            .withStringType(gsonSettings.getStringType())
            .withTypeAdapters(gsonSettings.getTypeAdapters())
            .withFactories(gsonSettings.getFactories());
    }

    public @NotNull Builder mutate() {
        return from(this);
    }

    public static class Builder implements ClassBuilder<GsonSettings> {

        private Optional<String> dateFormat = Optional.empty();
        private FormattingStyle style = FormattingStyle.COMPACT;
        private boolean serializingNulls;
        private StringType stringType = StringType.DEFAULT;
        private ConcurrentMap<Type, Object> typeAdapters = Concurrent.newMap();
        private ConcurrentList<TypeAdapterFactory> factories = Concurrent.newList();

        public @NotNull Builder isPrettyPrint() {
            return this.isPrettyPrint(true);
        }

        public @NotNull Builder isPrettyPrint(boolean prettyPrint) {
            this.style = prettyPrint ? FormattingStyle.PRETTY : FormattingStyle.COMPACT;
            return this;
        }

        public @NotNull Builder isSerializingNulls() {
            return this.isSerializingNulls(true);
        }

        public @NotNull Builder isSerializingNulls(boolean value) {
            this.serializingNulls = value;
            return this;
        }

        public @NotNull Builder removeTypeAdapter(@NotNull Type type) {
            this.typeAdapters.remove(type);
            return this;
        }

        public @NotNull Builder withDateFormat(@Nullable String dateFormat) {
            this.dateFormat = Optional.ofNullable(dateFormat);
            return this;
        }

        public @NotNull Builder withDateFormat(@PrintFormat @Nullable String dateFormat, @Nullable Object... args) {
            this.dateFormat = StringUtil.formatNullable(dateFormat, args);
            return this;
        }

        public @NotNull Builder withDateFormat(@NotNull Optional<String> dateFormat) {
            this.dateFormat = dateFormat;
            return this;
        }

        public @NotNull Builder withFactories(@NotNull TypeAdapterFactory... factories) {
            this.factories.addAll(factories);
            return this;
        }

        public @NotNull Builder withFactories(@NotNull Collection<TypeAdapterFactory> factories) {
            this.factories.addAll(factories);
            return this;
        }

        public @NotNull Builder withStyle(@NotNull FormattingStyle style) {
            this.style = style;
            return this;
        }

        /**
         * Sets the empty/null string handling type for this gson instance.
         *
         * @param stringType the {@link StringType} specifying how empty and null strings should be handled
         */
        public @NotNull Builder withStringType(@NotNull StringType stringType) {
            this.stringType = stringType;
            return this;
        }

        /**
         * Add a {@link TypeAdapter} to the gson instance for serialization and deserialization.
         *
         * @param type the type definition for the type adapter being registered
         * @param typeAdapter the {@link TypeAdapter} for serialization and deserialization
         */
        public <T> @NotNull Builder withTypeAdapter(@NotNull Type type, @NotNull TypeAdapter<T> typeAdapter) {
            return this._withTypeAdapter(type, typeAdapter);
        }

        /**
         * Add a {@link TypeAdapter} to the gson instance for instance creation.
         *
         * @param type the type definition for the instance creator being registered
         * @param instanceCreator the {@link InstanceCreator} for instance creation
         */
        public <T> @NotNull Builder withTypeAdapter(@NotNull Type type, @NotNull InstanceCreator<T> instanceCreator) {
            return this._withTypeAdapter(type, instanceCreator);
        }

        /**
         * Add a {@link JsonSerializer} to the gson instance for serialization.
         *
         * @param type the type definition for the json serializer being registered
         * @param serializer the {@link JsonSerializer} for serialization
         */
        public <T> @NotNull Builder withTypeAdapter(@NotNull Type type, @NotNull JsonSerializer<T> serializer) {
            return this._withTypeAdapter(type, serializer);
        }

        /**
         * Add a {@link JsonDeserializer} to the gson instance for deserialization.
         *
         * @param type the type definition for the json deserializer being registered
         * @param deserializer the {@link JsonDeserializer} for deserialization
         */
        public <T> @NotNull Builder withTypeAdapter(@NotNull Type type, @NotNull JsonDeserializer<T> deserializer) {
            return this._withTypeAdapter(type, deserializer);
        }

        private @NotNull Builder _withTypeAdapter(@NotNull Type type, @NotNull Object typeAdapter) {
            this.typeAdapters.put(type, typeAdapter);
            return this;
        }

        public @NotNull Builder withTypeAdapters(@NotNull Collection<Map.Entry<Type, Object>> typeAdapters) {
            typeAdapters.forEach(entry -> this.typeAdapters.put(entry.getKey(), entry.getValue()));
            return this;
        }

        public @NotNull Builder withTypeAdapters(@NotNull Map<Type, Object> typeAdapters) {
            this.typeAdapters.putAll(typeAdapters);
            return this;
        }

        @Override
        public @NotNull GsonSettings build() {
            return new GsonSettings(
                this.dateFormat,
                this.style,
                this.serializingNulls,
                this.stringType,
                this.typeAdapters,
                this.factories
            );
        }

    }

    public enum StringType {

        /**
         * Empty and null strings behave as normal.
         */
        DEFAULT,
        /**
         * Null strings are treated as empty.
         */
        EMPTY,
        /**
         * Empty strings are treated as null.
         */
        NULL

    }

}
