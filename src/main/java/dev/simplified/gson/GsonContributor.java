package dev.simplified.gson;

import com.google.gson.ExclusionStrategy;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ServiceLoader;

/**
 * Extension point for contributing {@link TypeAdapter}s, {@link TypeAdapterFactory factories},
 * or {@link ExclusionStrategy exclusion strategies} to {@link GsonSettings#defaults()}
 * without owning a static {@link com.google.gson.Gson} instance.
 * <p>
 * Contributors are discovered via {@link ServiceLoader} from every classpath entry that
 * declares a {@code META-INF/services/dev.simplified.gson.GsonContributor} resource, and
 * applied in ascending {@link #priority()} order after the built-in adapters and factories
 * have been registered.
 *
 * @see GsonSettings#defaults()
 * @see GsonSettings.Builder#apply(GsonContributor)
 */
public interface GsonContributor {

    /**
     * Contributes type adapters, factories, or exclusion strategies to {@code builder}.
     *
     * @param builder the settings builder to mutate
     */
    void contribute(GsonSettings.@NotNull Builder builder);

    /**
     * Ordering for {@link #contribute(GsonSettings.Builder)} application; lower runs first.
     * <p>
     * Defaults to {@code 0}. Override to register after (higher value) or before (negative
     * value) other contributors - for example, an {@link ExclusionStrategy} that wraps
     * earlier-registered adapters should use a positive value so it runs last.
     */
    default int priority() {
        return 0;
    }

}
