package dev.simplified.gson;

import dev.simplified.gson.factory.LenientTypeAdapterFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.util.Map Map} or {@link java.util.Collection Collection} field
 * for lenient deserialization.
 * <p>
 * During deserialization, entries whose key or value types are incompatible with the
 * field's declared generic type are silently filtered out and stored as overflow.
 * During serialization, the overflow entries are merged back into the JSON output,
 * preserving round-trip fidelity.
 * <p>
 * Example:
 * <pre>{@code
 * @Lenient
 * private ConcurrentMap<String, Integer> kills = Concurrent.newMap();
 * }</pre>
 * If the JSON contains {@code "kills": {"zombie_1": 5, "last_killed_mob": "ashfang_200"}},
 * the {@code "last_killed_mob"} entry is filtered out (its value is a String, not Integer)
 * and stored as overflow. The map only contains {@code {"zombie_1": 5}}.
 *
 * @see Extract
 * @see LenientTypeAdapterFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Lenient { }
