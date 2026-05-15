package dev.simplified.gson;

import dev.simplified.gson.factory.CollapseTypeAdapterFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

/**
 * Marks a {@link Map Map} or {@link List List} field whose JSON
 * source is a JSON object, injecting each entry's key into the value object via a
 * companion {@link Key @Key} field.
 * <p>
 * Two field shapes are supported:
 * <ul>
 *     <li><b>{@code Map<K, V>}</b> - deserialized normally as a map, then each value's
 *         {@link Key @Key} field is set to the entry's key.</li>
 *     <li><b>{@code List<V>}</b> - the JSON object's entries are deserialized as
 *         individual {@code V} instances with the {@link Key @Key} field set to the
 *         entry's key, then collected into the list.</li>
 * </ul>
 * Both shapes produce identical {@code V} instances with the key injected; the
 * annotation is dual-function for developer preference.
 * <p>
 * Serialization is fully reversible: the factory reads the {@link Key @Key} field
 * from each value to reconstruct the original JSON object.
 * <p>
 * Example (map form):
 * <pre>{@code
 * @Collapse
 * @SerializedName("slayer_bosses")
 * private ConcurrentMap<String, SlayerBoss> bosses = Concurrent.newMap();
 * }</pre>
 * Example (list form):
 * <pre>{@code
 * @Collapse
 * @SerializedName("slayer_bosses")
 * private ConcurrentList<SlayerBoss> bosses = Concurrent.newList();
 * }</pre>
 * Both deserialize JSON {@code {"zombie": {"xp": 100}, "spider": {"xp": 50}}} with
 * each {@code SlayerBoss} having its {@link Key @Key} field set to {@code "zombie"}
 * or {@code "spider"}.
 *
 * @see Key
 * @see CollapseTypeAdapterFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Collapse {
}
