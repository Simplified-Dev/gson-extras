package dev.simplified.gson;

import com.google.gson.annotations.SerializedName;
import dev.simplified.gson.factory.CaptureTypeAdapterFactory;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * Marks a {@link Map Map} field to capture dynamic JSON entries that do not
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
 * matching their key affixes against the value class's field serialized names, then
 * each group is deserialized as an instance of that class.
 * <p>
 * Affix direction is controlled by the value class field's
 * {@link SerializedName @SerializedName}:
 * <ul>
 *     <li><b>Prefix</b> - {@code ^} at start or {@code _} at end
 *         (e.g. {@code @SerializedName("^toggle_")} or {@code @SerializedName("toggle_")})</li>
 *     <li><b>Suffix</b> - {@code $} at end or {@code _} at start
 *         (e.g. {@code @SerializedName("_bronze$")} or {@code @SerializedName("_bronze")})</li>
 *     <li><b>Auto suffix</b> (default) - plain names are treated as suffixes with
 *         {@code _} prepended (e.g. field {@code bronze} matches {@code baseName_bronze})</li>
 *     <li><b>Bare field</b> - {@code @SerializedName("")} matches the base key itself</li>
 * </ul>
 * <p>
 * Example (simple capture):
 * <pre>{@code
 * @Capture(filter = "^dojo_points_")
 * private ConcurrentMap<Type, Integer> points = Concurrent.newMap();
 * }</pre>
 * JSON {@code {"dojo_points_FORCE": 100}} produces {@code points = {FORCE: 100}}.
 * <p>
 * Example (suffix grouping):
 * <pre>{@code
 * @Capture(filter = "^song_")
 * private ConcurrentMap<String, Song> songs = Concurrent.newMap();
 * }</pre>
 * JSON {@code {"song_hymn_joy_completions": 3}} groups into
 * {@code songs = {hymn_joy: Song(completions=3)}}.
 * <p>
 * Example (prefix grouping):
 * <pre>{@code
 * // Value class with a bare field and a prefix field:
 * class Node {
 *     @SerializedName("") int level;
 *     @SerializedName("toggle_") boolean enabled = true;
 * }
 *
 * @Capture
 * private ConcurrentMap<String, Node> nodes = Concurrent.newMap();
 * }</pre>
 * JSON {@code {"mining_speed": 50, "toggle_mining_speed": true}} groups into
 * {@code nodes = {mining_speed: Node(level=50, enabled=true)}}.
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

    /**
     * When {@code true}, the factory descends into the field's own JSON object
     * (identified by {@link SerializedName @SerializedName},
     * {@link SerializedPath @SerializedPath}, or the field name) before applying
     * capture logic to its entries.
     * <p>
     * This eliminates the need for a wrapper class when capturing from a named
     * nested JSON object.
     * <p>
     * Example:
     * <pre>{@code
     * @Capture(filter = "^level_", descend = true)
     * @SerializedName("claimed_levels")
     * private ConcurrentMap<Integer, ClaimedLevel> claimedLevels = Concurrent.newMap();
     * }</pre>
     * JSON {@code {"claimed_levels": {"level_1": true, "level_8_special": true}}}
     * descends into the {@code claimed_levels} object and captures its entries.
     *
     * @return {@code true} to descend into the named JSON object before capturing
     */
    boolean descend() default false;

}
