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
import com.google.gson.reflect.TypeToken;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.adapter.ColorTypeAdapter;
import dev.simplified.gson.adapter.InstantTypeAdapter;
import dev.simplified.gson.adapter.OffsetDateTimeTypeAdapter;
import dev.simplified.gson.adapter.StringTypeAdapter;
import dev.simplified.gson.adapter.UUIDTypeAdapter;
import dev.simplified.gson.factory.CaptureTypeAdapterFactory;
import dev.simplified.gson.factory.CaseInsensitiveEnumTypeAdapterFactory;
import dev.simplified.gson.factory.CollapseTypeAdapterFactory;
import dev.simplified.gson.factory.LenientTypeAdapterFactory;
import dev.simplified.gson.factory.OptionalTypeAdapterFactory;
import dev.simplified.gson.factory.PostInitTypeAdapterFactory;
import dev.simplified.gson.factory.SerializedPathTypeAdaptorFactory;
import dev.simplified.gson.factory.SplitTypeAdapterFactory;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.StreamSupport;

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

    /**
     * Date format pattern passed to {@link GsonBuilder#setDateFormat(String)}, if present.
     */
    private final @NotNull Optional<String> dateFormat;

    /**
     * Output formatting style applied to the {@link Gson} instance.
     */
    private final @NotNull FormattingStyle style;

    /**
     * Whether null values are included in serialized output.
     */
    private final boolean serializingNulls;

    /**
     * Strategy for handling empty and null strings during serialization.
     */
    private final @NotNull StringType stringType;

    /**
     * Per-type adapters registered with {@link GsonBuilder#registerTypeAdapter(Type, Object)}.
     */
    private final @NotNull ConcurrentMap<Type, Object> typeAdapters;

    /**
     * Adapter factories registered with {@link GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory)}.
     */
    private final @NotNull ConcurrentList<TypeAdapterFactory> factories;

    /**
     * Exclusion strategies applied to both serialization and deserialization.
     */
    private final @NotNull ConcurrentList<ExclusionStrategy> exclusionStrategies;

    /**
     * Whether {@link #create()} should warm the resulting Gson's internal {@code TypeAdapter}
     * cache for the union of {@link #prewarmTypes} and {@link #typeAdapters typeAdapters.keySet()}
     * before returning. Defaults to {@code true} - any caller that registers a custom type
     * adapter or an explicit prewarm type gets adapter resolution done eagerly. Set to
     * {@code false} to defer all adapter generation to first decode.
     */
    private final boolean prewarmAdapters;

    /**
     * Additional types whose {@code TypeAdapter} should be eagerly resolved inside
     * {@link #create()}, on top of the types already registered via {@link #typeAdapters}.
     * Empty by default; populate via {@link Builder#withPrewarmTypes(Type...)} when the
     * caller knows in advance which shapes will be decoded but has no custom adapter to
     * register (the typical Feign-contract-walk case - POJOs that use Gson's default
     * reflective adapter). Adapter resolution failures are swallowed per-type so one bad
     * type does not stop the rest.
     *
     * <p>Types that already appear as keys in {@link #typeAdapters} do not need to be
     * listed here - {@link #create()} warms them automatically when {@link #prewarmAdapters}
     * is on.</p>
     */
    private final @NotNull ConcurrentList<Type> prewarmTypes;

    /**
     * Creates a new empty {@link Builder}.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

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

        Gson gson = builder.create();

        if (this.prewarmAdapters) {
            // Warm both the explicit prewarm list and every type that already has a custom
            // adapter registered. Registering a custom adapter implies the caller cares about
            // that type, so eagerly resolving it matches intent.
            if (!this.prewarmTypes.isEmpty())
                prewarm(gson, this.prewarmTypes);
            if (!this.typeAdapters.isEmpty())
                prewarm(gson, this.typeAdapters.keySet());
        }

        return gson;
    }

    /**
     * Eagerly resolves and caches the {@code TypeAdapter} for every type in {@code types}
     * against the given {@code Gson} instance, so that subsequent deserialisation of those
     * types skips the first-request adapter generation cost.
     * <p>
     * Adapter resolution failures are swallowed per-type - a single bad type does not abort
     * the warm-up of the rest. This method exposes the same warm-up that {@link #create()}
     * performs automatically when {@link Builder#withPrewarmTypes(Type...)} is used, for
     * callers that hold a raw {@link Gson} instead of a {@code GsonSettings}.
     *
     * @param gson the Gson instance whose adapter cache should be warmed
     * @param types the types to resolve adapters for
     * @return the same {@code gson} reference for fluent chaining
     */
    public static @NotNull Gson prewarm(@NotNull Gson gson, @NotNull Iterable<Type> types) {
        for (Type type : types) {
            try {
                gson.getAdapter(TypeToken.get(type));
            } catch (Throwable ignored) {
                // A single bad type must not abort warming the rest. Gson caches the
                // failure internally so we will not retry on subsequent calls.
            }
        }
        return gson;
    }

    /**
     * Creates a {@link GsonSettings} pre-configured with all built-in type adapters
     * and factories.
     * <p>
     * The returned settings include:
     * <ul>
     *   <li><b>Type adapters</b> - {@link ColorTypeAdapter}, {@link InstantTypeAdapter},
     *       {@link OffsetDateTimeTypeAdapter}, {@link UUIDTypeAdapter}</li>
     *   <li><b>Factories</b> (registration order - Gson checks last-registered first, so
     *       the outermost wrapper is registered last):
     *       {@link CaseInsensitiveEnumTypeAdapterFactory}, {@link OptionalTypeAdapterFactory},
     *       {@link SplitTypeAdapterFactory}, {@link SerializedPathTypeAdaptorFactory},
     *       {@link LenientTypeAdapterFactory}, {@link CaptureTypeAdapterFactory},
     *       {@link CollapseTypeAdapterFactory}, {@link PostInitTypeAdapterFactory}</li>
     *   <li><b>SPI factories</b> - every {@link TypeAdapterFactory} discovered on the
     *       classpath via {@link ServiceLoader#load(Class)
     *       ServiceLoader.load(TypeAdapterFactory.class)}. The collections module
     *       ships {@code dev.simplified.collection.gson.ConcurrentTypeAdapterFactory}
     *       through this SPI to teach Gson the {@code Concurrent*} interfaces; downstream
     *       modules can ship their own factory by adding a
     *       {@code META-INF/services/com.google.gson.TypeAdapterFactory} service file</li>
     * </ul>
     * <p>
     * After the built-ins and SPI factories are registered, every {@link GsonContributor}
     * discovered on the classpath via {@link ServiceLoader} is applied in ascending
     * {@link GsonContributor#priority() priority} order. Downstream modules contribute
     * their own type adapters or exclusion strategies by shipping a
     * {@code META-INF/services/dev.simplified.gson.GsonContributor} service file.
     * <p>
     * Use {@link #create()} on the result to obtain a {@link Gson} instance, or
     * {@link #mutate()} to customize further before building.
     *
     * @return a fully configured {@link GsonSettings} with all built-in adapters, factories,
     *     and service-discovered contributor additions
     */
    public static @NotNull GsonSettings defaults() {
        Builder builder = builder()
            .withDateFormat("yyyy-MM-dd HH:mm:ss")
            .withStringType(GsonSettings.StringType.NULL)
            .withTypeAdapter(Color.class, new ColorTypeAdapter())
            .withTypeAdapter(Instant.class, new InstantTypeAdapter())
            .withTypeAdapter(OffsetDateTime.class, new OffsetDateTimeTypeAdapter())
            .withTypeAdapter(UUID.class, new UUIDTypeAdapter())
            .withFactories(
                new CaseInsensitiveEnumTypeAdapterFactory(),
                new OptionalTypeAdapterFactory(),
                new SplitTypeAdapterFactory(),
                new SerializedPathTypeAdaptorFactory(),
                new LenientTypeAdapterFactory(),
                new CaptureTypeAdapterFactory(),
                new CollapseTypeAdapterFactory(),
                new PostInitTypeAdapterFactory()
            );

        ServiceLoader.load(TypeAdapterFactory.class).forEach(builder::withFactories);

        StreamSupport.stream(ServiceLoader.load(GsonContributor.class).spliterator(), false)
            .sorted(Comparator.comparingInt(GsonContributor::priority))
            .forEach(builder::apply);

        return builder.build();
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
            .withExclusionStrategies(gsonSettings.getExclusionStrategies())
            .withPrewarmAdapters(gsonSettings.isPrewarmAdapters())
            .withPrewarmTypes(gsonSettings.getPrewarmTypes());
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
        private boolean prewarmAdapters = true;
        private final ConcurrentList<Type> prewarmTypes = Concurrent.newList();

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

        /**
         * Applies a {@link GsonContributor} to this builder, letting downstream modules
         * contribute adapters, factories, or exclusion strategies without owning a static
         * {@link Gson} instance.
         *
         * @param contributor the contributor to apply
         */
        public @NotNull Builder apply(@NotNull GsonContributor contributor) {
            contributor.contribute(this);
            return this;
        }

        /**
         * Sets whether the resulting {@link Gson} should eagerly resolve adapters for the
         * registered {@link #withPrewarmTypes(Type...) prewarm types} when
         * {@link GsonSettings#create()} is invoked.
         * <p>
         * Defaults to {@code true}. Set to {@code false} to defer adapter generation to
         * first decode (useful for tests that want a fully cold Gson, or for debugging
         * adapter-generation issues).
         *
         * @param prewarmAdapters whether to warm adapters at create-time
         * @return this builder
         */
        public @NotNull Builder withPrewarmAdapters(boolean prewarmAdapters) {
            this.prewarmAdapters = prewarmAdapters;
            return this;
        }

        /**
         * Registers one or more types whose {@code TypeAdapter} should be eagerly resolved
         * inside {@link GsonSettings#create()}.
         * <p>
         * Subsequent deserialisation of the registered types skips the first-request adapter
         * generation cost (typically 1-5 ms per fresh type). Adapter resolution is bounded
         * by {@link GsonSettings#prewarm(Gson, Iterable)} which swallows per-type failures so
         * a single malformed type cannot block the warm-up of others.
         *
         * @param types the types to warm at create-time
         * @return this builder
         */
        public @NotNull Builder withPrewarmTypes(@NotNull Type... types) {
            this.prewarmTypes.addAll(Arrays.asList(types));
            return this;
        }

        /**
         * Registers a collection of types whose {@code TypeAdapter} should be eagerly
         * resolved inside {@link GsonSettings#create()}.
         *
         * @param types the types to warm at create-time
         * @return this builder
         */
        public @NotNull Builder withPrewarmTypes(@NotNull Iterable<Type> types) {
            for (Type type : types) this.prewarmTypes.add(type);
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
                this.exclusionStrategies,
                this.prewarmAdapters,
                this.prewarmTypes
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
