package dev.simplified.io.gson;

import dev.simplified.gson.factory.LenientTypeAdapterFactory;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Extracts a specific entry from a {@link Lenient @Lenient} field's filtered overflow
 * into a typed companion field.
 * <p>
 * The {@link #value()} is a dot-separated path where the first segment is the Java
 * field name of the {@link Lenient @Lenient} source and the remaining segments
 * identify the JSON key to extract from the overflow.
 * <p>
 * Example:
 * <pre>{@code
 * @Lenient
 * private ConcurrentMap<String, Integer> kills = Concurrent.newMap();
 *
 * @Extract("kills.last_killed_mob")
 * private String lastKilledMob;
 * }</pre>
 * The {@code "last_killed_mob"} entry, which was filtered from the {@code kills} map
 * because its value is a String rather than Integer, is deserialized into
 * {@code lastKilledMob} instead.
 *
 * @see Lenient
 * @see LenientTypeAdapterFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Extract {

    /**
     * Dot-separated path: {@code "sourceField.jsonKey"}.
     * <p>
     * The first segment is the Java field name of the {@link Lenient @Lenient} source.
     * The remaining segments identify the JSON key to extract from the source's
     * filtered overflow.
     *
     * @return the dot-separated extraction path
     */
    @NotNull String value();

}
