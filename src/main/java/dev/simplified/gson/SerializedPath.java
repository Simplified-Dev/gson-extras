package dev.simplified.io.gson;

import com.google.gson.annotations.SerializedName;
import dev.simplified.gson.factory.SerializedPathTypeAdaptorFactory;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a field to a nested JSON path using dot-separated segments.
 * <p>
 * During deserialization, the adapter navigates the JSON tree along the path
 * to extract the value. During serialization, the flat field is removed and
 * re-inserted at the correct nested position.
 * <p>
 * Example:
 * <pre>{@code
 * @SerializedPath("stats.combat.strength")
 * private int strength;
 * }</pre>
 * reads from and writes to {@code {"stats": {"combat": {"strength": 200}}}}.
 * <p>
 * Can be combined with {@link SerializedName @SerializedName}
 * to control the flat key used by the delegate adapter.
 *
 * @see SerializedPathTypeAdaptorFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SerializedPath {

    /**
     * @return the dot-separated path to the nested JSON value
     */
    @NotNull String value();

}
