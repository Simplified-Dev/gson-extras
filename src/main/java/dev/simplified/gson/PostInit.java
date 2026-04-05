package dev.simplified.io.gson;

import dev.simplified.gson.factory.PostInitTypeAdapterFactory;

/**
 * Callback interface for post-deserialization initialization.
 * <p>
 * Classes implementing this interface have their {@link #postInit()} method
 * invoked automatically by {@link PostInitTypeAdapterFactory}
 * after Gson completes deserialization. This allows derived or transient fields
 * to be computed from the deserialized state.
 * <p>
 * Exceptions thrown from {@code postInit()} are logged and swallowed - the
 * deserialized object is still returned.
 *
 * @see PostInitTypeAdapterFactory
 */
public interface PostInit {

    /**
     * Performs post-deserialization initialization.
     */
    void postInit();

}