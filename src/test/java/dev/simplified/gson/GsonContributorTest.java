package dev.simplified.gson;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class GsonContributorTest {

    // ──── Builder#apply(GsonContributor) ────

    @Nested
    class BuilderApply {

        @Test
        public void apply_inlineLambda_registersTypeAdapter() {
            Gson gson = GsonSettings.builder()
                .apply(builder -> builder.withTypeAdapter(Marker.class, new PrefixSerializer("inline")))
                .build()
                .create();

            assertThat(gson.toJson(new Marker("hello")), is("\"inline:hello\""));
        }

    }

    // ──── defaults() ServiceLoader discovery ────

    @Nested
    class ServiceLoaderDiscovery {

        @Test
        public void defaults_discoversContributorViaServiceFile() {
            Gson gson = GsonSettings.defaults().create();

            // TestMarkerContributor is registered via
            // src/test/resources/META-INF/services/dev.simplified.gson.GsonContributor
            assertThat(gson.toJson(new TestMarker("ping")), is("\"test-marker:ping\""));
        }

        @Test
        public void defaults_appliesHigherPriorityLast_soItWins() {
            Gson gson = GsonSettings.defaults().create();

            // LowPriorityContributor (priority 0) runs before HighPriorityContributor (priority 100).
            // Both register an adapter for PriorityMarker; the later registration overwrites the
            // earlier in the typeAdapters map, so "high" wins.
            assertThat(gson.toJson(new PriorityMarker("val")), is("\"high:val\""));
        }

    }

    // ──── fixtures ────

    interface ValueCarrier {
        @NotNull String value();
    }

    public record Marker(@Override @NotNull String value) implements ValueCarrier {}

    public record TestMarker(@Override @NotNull String value) implements ValueCarrier {}

    public record PriorityMarker(@Override @NotNull String value) implements ValueCarrier {}

    @RequiredArgsConstructor
    static class PrefixSerializer implements JsonSerializer<ValueCarrier> {

        private final @NotNull String prefix;

        @Override
        public JsonElement serialize(@NotNull ValueCarrier src, @NotNull Type typeOfSrc, @NotNull JsonSerializationContext context) {
            return new JsonPrimitive(this.prefix + ":" + src.value());
        }

    }

    public static final class TestMarkerContributor implements GsonContributor {

        @Override
        public void contribute(GsonSettings.@NotNull Builder builder) {
            builder.withTypeAdapter(TestMarker.class, new PrefixSerializer("test-marker"));
        }

    }

    public static final class LowPriorityContributor implements GsonContributor {

        @Override
        public void contribute(GsonSettings.@NotNull Builder builder) {
            builder.withTypeAdapter(PriorityMarker.class, new PrefixSerializer("low"));
        }

        @Override
        public int priority() {
            return 0;
        }

    }

    public static final class HighPriorityContributor implements GsonContributor {

        @Override
        public void contribute(GsonSettings.@NotNull Builder builder) {
            builder.withTypeAdapter(PriorityMarker.class, new PrefixSerializer("high"));
        }

        @Override
        public int priority() {
            return 100;
        }

    }

}
