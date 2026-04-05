package dev.simplified.gson;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import dev.simplified.collection.concurrent.Concurrent;
import dev.simplified.collection.concurrent.ConcurrentList;
import dev.simplified.collection.concurrent.ConcurrentMap;
import dev.simplified.gson.adapter.StringTypeAdapter;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration for building {@link Gson} instances.
 * <p>
 * Encapsulates formatting style, null serialization, date format, custom type
 * adapters, and {@link TypeAdapterFactory} registrations. Use {@link #builder()}
 * to construct a new instance or {@link #mutate()} to derive a modified copy.
 * <p>
 * Call {@link #create()} to produce a configured {@link Gson} instance.
 *
 * @see Builder
 * @see StringType
 */
@Getter
@RequiredArgsConstructor
public class GsonSettings {

    /** Date format pattern passed to {@link GsonBuilder#setDateFormat(String)}, if present. */
    private final @NotNull Optional<String> dateFormat;

    /** Output formatting style applied to the {@link Gson} instance. */
    private final @NotNull FormattingStyle style;

    /** Whether null values are included in serialized output. */
    private final boolean serializingNulls;

    /** Strategy for handling empty and null strings during serialization. */
    private final @NotNull StringType stringType;

    /** Per-type adapters registered with {@link GsonBuilder#registerTypeAdapter(Type, Object)}. */
    private final @NotNull ConcurrentMap<Type, Object> typeAdapters;

    /** Adapter factories registered with {@link GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory)}. */
    private final @NotNull ConcurrentList<TypeAdapterFactory> factories;

    /** Exclusion strategies applied to both serialization and deserialization. */
    private final @NotNull ConcurrentList<ExclusionStrategy> exclusionStrategies;

    /**
     * Builds a new {@link Gson} instance from this configuration.
     *
     * @return a configured {@link Gson} instance
     */
    public @NotNull Gson create() {
        GsonBuilder builder = new GsonBuilder();
        builder.setFormattingStyle(this.getStyle());
        builder.setDateFormat(this.dateFormat.orElse(null));
        this.getFactories().forEach(builder::registerTypeAdapterFactory);
        this.getTypeAdapters().forEach(builder::registerTypeAdapter);
        builder.registerTypeAdapter(String.class, new StringTypeAdapter(this.getStringType()));

        if (this.isSerializingNulls())
            builder.serializeNulls();

        this.getExclusionStrategies().forEach(strategy -> {
            builder.addSerializationExclusionStrategy(strategy);
            builder.addDeserializationExclusionStrategy(strategy);
        });

        return builder.create();
    }

    /**
     * Creates a new empty {@link Builder}.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Creates a {@link Builder} pre-populated with the values from the given settings.
     *
     * @param gsonSettings the settings to copy from
     * @return a pre-populated builder
     */
    public static @NotNull Builder from(@NotNull GsonSettings gsonSettings) {
        return builder()
            .withDateFormat(gsonSettings.getDateFormat())
            .withStyle(gsonSettings.getStyle())
            .isSerializingNulls(gsonSettings.isSerializingNulls())
            .withStringType(gsonSettings.getStringType())
            .withTypeAdapters(gsonSettings.getTypeAdapters())
            .withFactories(gsonSettings.getFactories())
            .withExclusionStrategies(gsonSettings.getExclusionStrategies());
    }

    /**
     * Returns a {@link Builder} pre-populated with this instance's values for modification.
     *
     * @return a pre-populated builder
     */
    public @NotNull Builder mutate() {
        return from(this);
    }

    /**
     * Fluent builder for constructing {@link GsonSettings} instances.
     */
    public static class Builder {

        private Optional<String> dateFormat = Optional.empty();
        private FormattingStyle style = FormattingStyle.COMPACT;
        private boolean serializingNulls;
        private StringType stringType = StringType.DEFAULT;
        private ConcurrentMap<Type, Object> typeAdapters = Concurrent.newMap();
        private ConcurrentList<TypeAdapterFactory> factories = Concurrent.newList();
        private ConcurrentList<ExclusionStrategy> exclusionStrategies = Concurrent.newList();

        /**
         * Enables pretty-print formatting.
         */
        public @NotNull Builder isPrettyPrint() {
            return this.isPrettyPrint(true);
        }

        /**
         * Sets whether pretty-print formatting is enabled.
         *
         * @param prettyPrint {@code true} for {@link FormattingStyle#PRETTY}, {@code false} for {@link FormattingStyle#COMPACT}
         */
        public @NotNull Builder isPrettyPrint(boolean prettyPrint) {
            this.style = prettyPrint ? FormattingStyle.PRETTY : FormattingStyle.COMPACT;
            return this;
        }

        /**
         * Enables null serialization.
         */
        public @NotNull Builder isSerializingNulls() {
            return this.isSerializingNulls(true);
        }

        /**
         * Sets whether null values are included in serialized output.
         *
         * @param value {@code true} to serialize nulls
         */
        public @NotNull Builder isSerializingNulls(boolean value) {
            this.serializingNulls = value;
            return this;
        }

        /**
         * Removes a previously registered type adapter for the given type.
         *
         * @param type the type whose adapter should be removed
         */
        public @NotNull Builder removeTypeAdapter(@NotNull Type type) {
            this.typeAdapters.remove(type);
            return this;
        }

        /**
         * Sets the date format pattern.
         *
         * @param dateFormat the date format string, or {@code null} to clear
         */
        public @NotNull Builder withDateFormat(@Nullable String dateFormat) {
            this.dateFormat = Optional.ofNullable(dateFormat);
            return this;
        }

        /**
         * Sets the date format pattern using a format string and arguments.
         *
         * @param dateFormat the format string, or {@code null} to clear
         * @param args the format arguments
         */
        public @NotNull Builder withDateFormat(@PrintFormat @Nullable String dateFormat, @Nullable Object... args) {
            this.dateFormat = StringUtil.formatNullable(dateFormat, args);
            return this;
        }

        /**
         * Sets the date format pattern from an {@link Optional}.
         *
         * @param dateFormat the date format, or {@link Optional#empty()} to clear
         */
        public @NotNull Builder withDateFormat(@NotNull Optional<String> dateFormat) {
            this.dateFormat = dateFormat;
            return this;
        }

        /**
         * Appends one or more {@link TypeAdapterFactory} instances.
         *
         * @param factories the factories to register
         */
        public @NotNull Builder withFactories(@NotNull TypeAdapterFactory... factories) {
            this.factories.addAll(factories);
            return this;
        }

        /**
         * Appends a collection of {@link TypeAdapterFactory} instances.
         *
         * @param factories the factories to register
         */
        public @NotNull Builder withFactories(@NotNull Collection<TypeAdapterFactory> factories) {
            this.factories.addAll(factories);
            return this;
        }

        /**
         * Appends one or more {@link ExclusionStrategy} instances applied to both
         * serialization and deserialization.
         *
         * @param strategies the exclusion strategies to register
         */
        public @NotNull Builder withExclusionStrategies(@NotNull ExclusionStrategy... strategies) {
            this.exclusionStrategies.addAll(strategies);
            return this;
        }

        /**
         * Appends a collection of {@link ExclusionStrategy} instances.
         *
         * @param strategies the exclusion strategies to register
         */
        public @NotNull Builder withExclusionStrategies(@NotNull Collection<ExclusionStrategy> strategies) {
            this.exclusionStrategies.addAll(strategies);
            return this;
        }

        /**
         * Sets the output {@link FormattingStyle}.
         *
         * @param style the formatting style
         */
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

        /**
         * Registers type adapters from a collection of entries.
         *
         * @param typeAdapters the entries to register
         */
        public @NotNull Builder withTypeAdapters(@NotNull Collection<Map.Entry<Type, Object>> typeAdapters) {
            typeAdapters.forEach(entry -> this.typeAdapters.put(entry.getKey(), entry.getValue()));
            return this;
        }

        /**
         * Registers type adapters from a map.
         *
         * @param typeAdapters the type-to-adapter mappings to register
         */
        public @NotNull Builder withTypeAdapters(@NotNull Map<Type, Object> typeAdapters) {
            this.typeAdapters.putAll(typeAdapters);
            return this;
        }

        /** {@inheritDoc} */
        public @NotNull GsonSettings build() {
            return new GsonSettings(
                this.dateFormat,
                this.style,
                this.serializingNulls,
                this.stringType,
                this.typeAdapters,
                this.factories,
                this.exclusionStrategies
            );
        }

    }

    /**
     * Strategy controlling how empty and null strings are handled during serialization.
     */
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
