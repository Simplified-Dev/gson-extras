package dev.simplified.gson;

import dev.simplified.gson.factory.CaptureTypeAdapterFactory;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.util.Map Map} field to capture dynamic JSON entries that do not
 * correspond to any known (declared) field on the enclosing class.
 * <p>
 * Behavior depends on {@link #filter()}:
 * <ul>
 *     <li><b>Empty filter (catch-all):</b> Collects all JSON entries whose keys do not
 *         match any known field or any filtered {@code @Capture} field. At most one
 *         catch-all is allowed per class.</li>
 *     <li><b>Regex filter:</b> Only entries whose key matches the regex are collected.
 *         The matched portion is stripped from the key via
 *         {@link String#replaceFirst(String, String) replaceFirst(filter, "")}. Multiple
 *         filtered {@code @Capture} fields can coexist on the same class.</li>
 * </ul>
 * <p>
 * All captured entries are type-filtered by the map's declared key and value types.
 * Incompatible entries are stored as overflow for round-trip fidelity.
 * <p>
 * When the map's value type is a class with fields (not a primitive, String, or enum),
 * the factory enters <b>class-value grouping mode</b>: entries are auto-grouped by
 * matching their key suffixes against the value class's field serialized names, then
 * each group is deserialized as an instance of that class.
 * <p>
 * Example (simple capture):
 * <pre>{@code
 * @Capture(filter = "^dojo_points_")
 * private ConcurrentMap<Type, Integer> points = Concurrent.newMap();
 * }</pre>
 * JSON {@code {"dojo_points_FORCE": 100}} produces {@code points = {FORCE: 100}}.
 * <p>
 * Example (class-value grouping):
 * <pre>{@code
 * @Capture(filter = "^song_")
 * private ConcurrentMap<String, Song> songs = Concurrent.newMap();
 * }</pre>
 * JSON {@code {"song_hymn_joy_completions": 3}} groups into
 * {@code songs = {hymn_joy: Song(completions=3)}}.
 *
 * @see CaptureTypeAdapterFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Capture {

    /**
     * Regex filter for selecting which JSON keys this field captures.
     * <p>
     * An empty string (the default) creates a catch-all that collects all
     * unmatched entries. A non-empty regex selects only keys that match, with
     * the matched portion stripped from the key.
     *
     * @return the regex filter pattern, or empty for catch-all
     */
    @Language("RegExp")
    @NotNull String filter() default "";

}
