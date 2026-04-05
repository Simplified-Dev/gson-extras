package dev.simplified.gson;

import dev.simplified.gson.factory.SplitTypeAdapterFactory;
import dev.simplified.collection.tuple.pair.Pair;
import dev.simplified.collection.tuple.pair.PairOptional;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Splits a single JSON string value by a literal delimiter into the two components
 * of a {@link Pair} or {@link PairOptional} field.
 * <p>
 * During deserialization, the string is split into at most two parts. Each part is
 * deserialized to the corresponding generic type argument of the field. During
 * serialization, the two components are serialized to strings and joined with the
 * delimiter.
 * <p>
 * For {@link PairOptional} fields, null or missing JSON values produce
 * {@link PairOptional#empty()}.
 * <p>
 * Example:
 * <pre>{@code
 * @SerializedName("last_caught")
 * @Split("/")
 * private PairOptional<Fish, Tier> lastCaught = PairOptional.empty();
 * }</pre>
 * JSON {@code "obfuscated_fish_1/bronze"} produces
 * {@code PairOptional.of(Fish.OBFUSCATED_FISH_1, Tier.BRONZE)}.
 *
 * @see SplitTypeAdapterFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Split {

    /**
     * The literal delimiter used to split the string value.
     *
     * @return the delimiter string
     */
    @NotNull String value();

}
