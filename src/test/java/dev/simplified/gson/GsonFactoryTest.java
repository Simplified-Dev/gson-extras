package dev.simplified.gson;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import dev.simplified.SimplifiedApi;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.tuple.pair.Pair;
import dev.simplified.collection.tuple.pair.PairOptional;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GsonFactoryTest {

    // ──── SerializedPathTypeAdaptorFactory ────

    @Nested
    class SerializedPathTests {

        @Getter
        @NoArgsConstructor
        static class PlayerStats {

            private String name;
            @SerializedPath("stats.health")
            private int health;
            @SerializedPath("stats.defense")
            private int defense;
            @SerializedPath("stats.combat.strength")
            private int strength;
            @SerializedPath("stats.combat.crit_damage")
            private double critDamage;
            @SerializedPath("perks.double_drops")
            private int doubleDrops;

        }

        @Getter
        @NoArgsConstructor
        static class WithSerializedName {

            private String id;
            @SerializedName("display_name")
            @SerializedPath("profile.display_name")
            private String displayName;
            @SerializedPath("profile.level")
            private int level;

        }

        @Getter
        @NoArgsConstructor
        static class WithOptional {

            private String key;
            @SerializedPath("metadata.description")
            private Optional<String> description = Optional.empty();
            @SerializedPath("metadata.version")
            private Optional<Integer> version = Optional.empty();

        }

        @Test
        public void readNestedPaths_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "TestPlayer",
                    "stats": {
                        "health": 100,
                        "defense": 50,
                        "combat": {
                            "strength": 200,
                            "crit_damage": 1.5
                        }
                    },
                    "perks": {
                        "double_drops": 3
                    }
                }
                """;

            PlayerStats stats = gson.fromJson(json, PlayerStats.class);

            assertThat(stats.getName(), is("TestPlayer"));
            assertThat(stats.getHealth(), is(100));
            assertThat(stats.getDefense(), is(50));
            assertThat(stats.getStrength(), is(200));
            assertThat(stats.getCritDamage(), is(1.5));
            assertThat(stats.getDoubleDrops(), is(3));
        }

        @Test
        public void writeNestedPaths_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "TestPlayer",
                    "stats": {
                        "health": 100,
                        "defense": 50,
                        "combat": {
                            "strength": 200,
                            "crit_damage": 1.5
                        }
                    },
                    "perks": {
                        "double_drops": 3
                    }
                }
                """;

            PlayerStats stats = gson.fromJson(json, PlayerStats.class);
            String output = gson.toJson(stats);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            // Verify flat fields
            assertThat(result.get("name").getAsString(), is("TestPlayer"));

            // Verify nested path fields are NOT flat
            assertThat(result.has("health"), is(false));
            assertThat(result.has("defense"), is(false));
            assertThat(result.has("strength"), is(false));
            assertThat(result.has("critDamage"), is(false));
            assertThat(result.has("doubleDrops"), is(false));

            // Verify nested structure
            JsonObject statsObj = result.getAsJsonObject("stats");
            assertThat(statsObj, is(notNullValue()));
            assertThat(statsObj.get("health").getAsInt(), is(100));
            assertThat(statsObj.get("defense").getAsInt(), is(50));

            JsonObject combatObj = statsObj.getAsJsonObject("combat");
            assertThat(combatObj, is(notNullValue()));
            assertThat(combatObj.get("strength").getAsInt(), is(200));
            assertThat(combatObj.get("crit_damage").getAsDouble(), is(1.5));

            JsonObject perksObj = result.getAsJsonObject("perks");
            assertThat(perksObj, is(notNullValue()));
            assertThat(perksObj.get("double_drops").getAsInt(), is(3));
        }

        @Test
        public void roundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "RoundTripPlayer",
                    "stats": {
                        "health": 250,
                        "defense": 120,
                        "combat": {
                            "strength": 350,
                            "crit_damage": 2.75
                        }
                    },
                    "perks": {
                        "double_drops": 5
                    }
                }
                """;

            PlayerStats first = gson.fromJson(json, PlayerStats.class);
            String serialized = gson.toJson(first);
            PlayerStats second = gson.fromJson(serialized, PlayerStats.class);

            assertThat(second.getName(), is(first.getName()));
            assertThat(second.getHealth(), is(first.getHealth()));
            assertThat(second.getDefense(), is(first.getDefense()));
            assertThat(second.getStrength(), is(first.getStrength()));
            assertThat(second.getCritDamage(), is(first.getCritDamage()));
            assertThat(second.getDoubleDrops(), is(first.getDoubleDrops()));
        }

        @Test
        public void writeWithSerializedName_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "id": "abc-123",
                    "profile": {
                        "display_name": "CoolUser",
                        "level": 42
                    }
                }
                """;

            WithSerializedName obj = gson.fromJson(json, WithSerializedName.class);
            String output = gson.toJson(obj);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            // Flat key from @SerializedName should be removed
            assertThat(result.has("display_name"), is(false));
            // Field name key should also not be present
            assertThat(result.has("displayName"), is(false));

            // Verify nested structure
            JsonObject profileObj = result.getAsJsonObject("profile");
            assertThat(profileObj, is(notNullValue()));
            assertThat(profileObj.get("display_name").getAsString(), is("CoolUser"));
            assertThat(profileObj.get("level").getAsInt(), is(42));

            // Roundtrip
            WithSerializedName roundTripped = gson.fromJson(output, WithSerializedName.class);
            assertThat(roundTripped.getDisplayName(), is("CoolUser"));
            assertThat(roundTripped.getLevel(), is(42));
        }

        @Test
        public void writeWithOptional_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "key": "test-key",
                    "metadata": {
                        "description": "A test item",
                        "version": 3
                    }
                }
                """;

            WithOptional obj = gson.fromJson(json, WithOptional.class);
            String output = gson.toJson(obj);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            // Verify nested structure
            JsonObject metadataObj = result.getAsJsonObject("metadata");
            assertThat(metadataObj, is(notNullValue()));
            assertThat(metadataObj.get("description").getAsString(), is("A test item"));
            assertThat(metadataObj.get("version").getAsInt(), is(3));

            // Roundtrip
            WithOptional roundTripped = gson.fromJson(output, WithOptional.class);
            assertThat(roundTripped.getDescription().orElse(null), is("A test item"));
            assertThat(roundTripped.getVersion().orElse(null), is(3));
        }

        @Test
        public void writeWithEmptyOptional_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "key": "empty-key"
                }
                """;

            WithOptional obj = gson.fromJson(json, WithOptional.class);
            String output = gson.toJson(obj);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            assertThat(result.get("key").getAsString(), is("empty-key"));

            // Empty optionals should still produce the nested path since serializeNulls is on
            JsonObject metadataObj = result.getAsJsonObject("metadata");
            assertThat(metadataObj, is(notNullValue()));

            // Roundtrip preserves empty optionals
            WithOptional roundTripped = gson.fromJson(output, WithOptional.class);
            assertThat(roundTripped.getKey(), is("empty-key"));
            assertThat(roundTripped.getDescription().isPresent(), is(false));
            assertThat(roundTripped.getVersion().isPresent(), is(false));
        }

    }

    // ──── OptionalTypeAdapterFactory ────

    @Nested
    class OptionalTypeAdapterTests {

        @Getter
        @NoArgsConstructor
        static class OptionalModel {

            private String name;
            private Optional<String> nickname = Optional.empty();
            private Optional<Integer> level = Optional.empty();
            private Optional<Double> score = Optional.empty();
            private Optional<Boolean> active = Optional.empty();

        }

        @Test
        public void serializePresentOptionals_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Alice",
                    "nickname": "Ace",
                    "level": 42,
                    "score": 98.5,
                    "active": true
                }
                """;

            OptionalModel model = gson.fromJson(json, OptionalModel.class);
            String output = gson.toJson(model);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            // Present optionals are unwrapped (not nested in an object)
            assertThat(result.get("name").getAsString(), is("Alice"));
            assertThat(result.get("nickname").getAsString(), is("Ace"));
            assertThat(result.get("level").getAsInt(), is(42));
            assertThat(result.get("score").getAsDouble(), is(98.5));
            assertThat(result.get("active").getAsBoolean(), is(true));
        }

        @Test
        public void serializeEmptyOptionals_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Bob"
                }
                """;

            OptionalModel model = gson.fromJson(json, OptionalModel.class);
            String output = gson.toJson(model);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            assertThat(result.get("name").getAsString(), is("Bob"));
            // Empty optionals are omitted when serializeNulls is off
            assertThat(result.has("nickname"), is(false));
            assertThat(result.has("level"), is(false));
        }

        @Test
        public void deserializeNullToEmpty_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Charlie",
                    "nickname": null,
                    "level": null
                }
                """;

            OptionalModel model = gson.fromJson(json, OptionalModel.class);

            assertThat(model.getName(), is("Charlie"));
            assertThat(model.getNickname().isPresent(), is(false));
            assertThat(model.getLevel().isPresent(), is(false));
        }

        @Test
        public void deserializeMissingToEmpty_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Diana"
                }
                """;

            OptionalModel model = gson.fromJson(json, OptionalModel.class);

            assertThat(model.getName(), is("Diana"));
            assertThat(model.getNickname().isPresent(), is(false));
            assertThat(model.getLevel().isPresent(), is(false));
            assertThat(model.getScore().isPresent(), is(false));
            assertThat(model.getActive().isPresent(), is(false));
        }

        @Test
        public void roundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Eve",
                    "nickname": "E",
                    "level": 99,
                    "score": 100.0,
                    "active": false
                }
                """;

            OptionalModel first = gson.fromJson(json, OptionalModel.class);
            String serialized = gson.toJson(first);
            OptionalModel second = gson.fromJson(serialized, OptionalModel.class);

            assertThat(second.getName(), is(first.getName()));
            assertThat(second.getNickname().orElse(null), is(first.getNickname().orElse(null)));
            assertThat(second.getLevel().orElse(null), is(first.getLevel().orElse(null)));
            assertThat(second.getScore().orElse(null), is(first.getScore().orElse(null)));
            assertThat(second.getActive().orElse(null), is(first.getActive().orElse(null)));
        }

        @Test
        public void roundTripWithEmptyOptionals_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Frank"
                }
                """;

            OptionalModel first = gson.fromJson(json, OptionalModel.class);
            String serialized = gson.toJson(first);
            OptionalModel second = gson.fromJson(serialized, OptionalModel.class);

            assertThat(second.getName(), is("Frank"));
            assertThat(second.getNickname().isPresent(), is(false));
            assertThat(second.getLevel().isPresent(), is(false));
            assertThat(second.getScore().isPresent(), is(false));
            assertThat(second.getActive().isPresent(), is(false));
        }

    }

    // ──── CaptureTypeAdapterFactory ────

    @Nested
    class CaptureTests {

        @Getter
        @NoArgsConstructor
        static class SimpleCaptureModel {

            private String name;
            private int level;
            @Capture
            private ConcurrentMap<String, Integer> data = Concurrent.newMap();

        }

        @Getter
        @NoArgsConstructor
        static class FilteredCaptureModel {

            private String name;
            @Capture(filter = "^stat_")
            private ConcurrentMap<String, Integer> stats = Concurrent.newMap();

        }

        enum DojoType {
            FORCE,
            STAMINA,
            MASTERY
        }

        @Getter
        @NoArgsConstructor
        static class EnumKeyCaptureModel {

            @Capture(filter = "^dojo_points_")
            private ConcurrentMap<DojoType, Integer> points = Concurrent.newMap();

        }

        @Getter
        @NoArgsConstructor
        static class MultiCaptureModel {

            private String id;
            @Capture(filter = "^points_")
            private ConcurrentMap<String, Integer> points = Concurrent.newMap();
            @Capture(filter = "^wave_")
            private ConcurrentMap<String, Integer> waves = Concurrent.newMap();
            @Capture
            private ConcurrentMap<String, Integer> extras = Concurrent.newMap();

        }

        @Getter
        @NoArgsConstructor
        static class CaptureWithSerializedNameModel {

            @SerializedName("display_name")
            private String displayName;
            private int level;
            @Capture
            private ConcurrentMap<String, Integer> data = Concurrent.newMap();

        }

        @Getter
        @NoArgsConstructor
        static class CaptureWithPostInitModel implements PostInit {

            private String name;
            @Capture
            private ConcurrentMap<String, Integer> data = Concurrent.newMap();
            private transient int total;

            @Override
            public void postInit() {
                this.total = this.data.values().stream().mapToInt(Integer::intValue).sum();
            }

        }

        @Getter
        @NoArgsConstructor
        static class InnerSong {

            @SerializedName("best_completion")
            private int bestCompletion;
            private int completions;
            @SerializedName("perfect_completions")
            private int perfectCompletions;

        }

        @Getter
        @NoArgsConstructor
        static class GroupingModel {

            private String name;
            @Capture
            private ConcurrentMap<String, InnerSong> songs = Concurrent.newMap();

        }

        @Getter
        @NoArgsConstructor
        static class FilteredGroupingModel {

            @SerializedName("claimed_talisman")
            private boolean talismanClaimed;
            @Capture(filter = "^song_")
            private ConcurrentMap<String, InnerSong> songs = Concurrent.newMap();

        }

        @Test
        public void readCapture_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Test",
                    "level": 5,
                    "extra_1": 10,
                    "extra_2": 20
                }
                """;

            SimpleCaptureModel model = gson.fromJson(json, SimpleCaptureModel.class);

            assertThat(model.getName(), is("Test"));
            assertThat(model.getLevel(), is(5));
            assertThat(model.getData(), aMapWithSize(2));
            assertThat(model.getData(), hasEntry("extra_1", 10));
            assertThat(model.getData(), hasEntry("extra_2", 20));
        }

        @Test
        public void writeCapture_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Test",
                    "level": 5,
                    "extra_1": 10,
                    "extra_2": 20
                }
                """;

            SimpleCaptureModel model = gson.fromJson(json, SimpleCaptureModel.class);
            String output = gson.toJson(model);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            assertThat(result.get("name").getAsString(), is("Test"));
            assertThat(result.get("level").getAsInt(), is(5));
            assertThat(result.get("extra_1").getAsInt(), is(10));
            assertThat(result.get("extra_2").getAsInt(), is(20));
        }

        @Test
        public void roundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "RoundTrip",
                    "level": 3,
                    "a": 1,
                    "b": 2
                }
                """;

            SimpleCaptureModel first = gson.fromJson(json, SimpleCaptureModel.class);
            String serialized = gson.toJson(first);
            SimpleCaptureModel second = gson.fromJson(serialized, SimpleCaptureModel.class);

            assertThat(second.getName(), is(first.getName()));
            assertThat(second.getLevel(), is(first.getLevel()));
            assertThat(second.getData(), is(first.getData()));
        }

        @Test
        public void emptyCapture_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Empty",
                    "level": 1
                }
                """;

            SimpleCaptureModel model = gson.fromJson(json, SimpleCaptureModel.class);

            assertThat(model.getName(), is("Empty"));
            assertThat(model.getLevel(), is(1));
            assertThat(model.getData(), anEmptyMap());
        }

        @Test
        public void typeFilteredCapture_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "TypeFilter",
                    "level": 1,
                    "valid": 42,
                    "invalid": "not_an_int"
                }
                """;

            SimpleCaptureModel model = gson.fromJson(json, SimpleCaptureModel.class);

            assertThat(model.getData(), aMapWithSize(1));
            assertThat(model.getData(), hasEntry("valid", 42));
        }

        @Test
        public void captureWithSerializedName_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "display_name": "Cool",
                    "level": 5,
                    "dynamic_1": 10
                }
                """;

            CaptureWithSerializedNameModel model = gson.fromJson(json, CaptureWithSerializedNameModel.class);

            assertThat(model.getDisplayName(), is("Cool"));
            assertThat(model.getLevel(), is(5));
            assertThat(model.getData(), aMapWithSize(1));
            assertThat(model.getData(), hasEntry("dynamic_1", 10));
        }

        @Test
        public void captureWithPostInit_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "PostInit",
                    "a": 3,
                    "b": 7
                }
                """;

            CaptureWithPostInitModel model = gson.fromJson(json, CaptureWithPostInitModel.class);

            assertThat(model.getName(), is("PostInit"));
            assertThat(model.getData(), aMapWithSize(2));
            assertThat(model.getTotal(), is(10));
        }

        @Test
        public void noCaptureField_passthrough_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "key": "test",
                    "value": 42
                }
                """;

            PostInitTests.PlainModel model = gson.fromJson(json, PostInitTests.PlainModel.class);

            assertThat(model.getKey(), is("test"));
            assertThat(model.getValue(), is(42));
        }

        @Test
        public void filteredCapture_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Filtered",
                    "stat_health": 100,
                    "stat_defense": 50,
                    "unrelated": 999
                }
                """;

            FilteredCaptureModel model = gson.fromJson(json, FilteredCaptureModel.class);

            assertThat(model.getName(), is("Filtered"));
            assertThat(model.getStats(), aMapWithSize(2));
            assertThat(model.getStats(), hasEntry("health", 100));
            assertThat(model.getStats(), hasEntry("defense", 50));
        }

        @Test
        public void filterWithEnumKey_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "dojo_points_FORCE": 100,
                    "dojo_points_STAMINA": 200,
                    "dojo_points_MASTERY": 300
                }
                """;

            EnumKeyCaptureModel model = gson.fromJson(json, EnumKeyCaptureModel.class);

            assertThat(model.getPoints(), aMapWithSize(3));
            assertThat(model.getPoints(), hasEntry(DojoType.FORCE, 100));
            assertThat(model.getPoints(), hasEntry(DojoType.STAMINA, 200));
            assertThat(model.getPoints(), hasEntry(DojoType.MASTERY, 300));
        }

        @Test
        public void multipleFilteredFields_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "id": "multi",
                    "points_a": 10,
                    "points_b": 20,
                    "wave_x": 5,
                    "other": 99
                }
                """;

            MultiCaptureModel model = gson.fromJson(json, MultiCaptureModel.class);

            assertThat(model.getId(), is("multi"));
            assertThat(model.getPoints(), aMapWithSize(2));
            assertThat(model.getPoints(), hasEntry("a", 10));
            assertThat(model.getPoints(), hasEntry("b", 20));
            assertThat(model.getWaves(), aMapWithSize(1));
            assertThat(model.getWaves(), hasEntry("x", 5));
            assertThat(model.getExtras(), aMapWithSize(1));
            assertThat(model.getExtras(), hasEntry("other", 99));
        }

        @Test
        public void filteredRoundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "RT",
                    "stat_health": 100,
                    "stat_defense": 50
                }
                """;

            FilteredCaptureModel first = gson.fromJson(json, FilteredCaptureModel.class);
            String serialized = gson.toJson(first);
            FilteredCaptureModel second = gson.fromJson(serialized, FilteredCaptureModel.class);

            assertThat(second.getName(), is(first.getName()));
            assertThat(second.getStats(), is(first.getStats()));
        }

        @Test
        public void classValueGrouping_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "Harp",
                    "hymn_joy_best_completion": 5,
                    "hymn_joy_completions": 10,
                    "hymn_joy_perfect_completions": 3,
                    "through_fire_best_completion": 2,
                    "through_fire_completions": 8,
                    "through_fire_perfect_completions": 1
                }
                """;

            GroupingModel model = gson.fromJson(json, GroupingModel.class);

            assertThat(model.getName(), is("Harp"));
            assertThat(model.getSongs(), aMapWithSize(2));
            assertThat(model.getSongs(), hasKey("hymn_joy"));
            assertThat(model.getSongs().get("hymn_joy").getBestCompletion(), is(5));
            assertThat(model.getSongs().get("hymn_joy").getCompletions(), is(10));
            assertThat(model.getSongs().get("hymn_joy").getPerfectCompletions(), is(3));
            assertThat(model.getSongs().get("through_fire").getBestCompletion(), is(2));
        }

        @Test
        public void classValueGroupingWithFilter_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "claimed_talisman": true,
                    "song_hymn_joy_best_completion": 5,
                    "song_hymn_joy_completions": 10,
                    "song_hymn_joy_perfect_completions": 3
                }
                """;

            FilteredGroupingModel model = gson.fromJson(json, FilteredGroupingModel.class);

            assertThat(model.isTalismanClaimed(), is(true));
            assertThat(model.getSongs(), aMapWithSize(1));
            assertThat(model.getSongs().get("hymn_joy").getBestCompletion(), is(5));
            assertThat(model.getSongs().get("hymn_joy").getCompletions(), is(10));
            assertThat(model.getSongs().get("hymn_joy").getPerfectCompletions(), is(3));
        }

        @Test
        public void classValueGroupingRoundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "claimed_talisman": true,
                    "song_hymn_joy_best_completion": 5,
                    "song_hymn_joy_completions": 10,
                    "song_hymn_joy_perfect_completions": 3
                }
                """;

            FilteredGroupingModel first = gson.fromJson(json, FilteredGroupingModel.class);
            String serialized = gson.toJson(first);
            FilteredGroupingModel second = gson.fromJson(serialized, FilteredGroupingModel.class);

            assertThat(second.isTalismanClaimed(), is(first.isTalismanClaimed()));
            assertThat(second.getSongs(), aMapWithSize(1));
            assertThat(second.getSongs().get("hymn_joy").getBestCompletion(), is(5));
            assertThat(second.getSongs().get("hymn_joy").getCompletions(), is(10));
            assertThat(second.getSongs().get("hymn_joy").getPerfectCompletions(), is(3));
        }

        @Getter
        @NoArgsConstructor
        static class BareEntryTierData {

            @SerializedName("")
            private int total;
            private int bronze;
            private int silver;
            private int gold;
            private int diamond;

        }

        @Getter
        @NoArgsConstructor
        static class BareEntryGroupingModel {

            private int count;
            @Capture
            private ConcurrentMap<String, BareEntryTierData> entries = Concurrent.newMap();

        }

        enum TrophyFish {
            BLOBFISH,
            GUSHER,
            GOLDEN_FISH
        }

        @Getter
        @NoArgsConstructor
        static class BareEntryEnumKeyModel {

            @Capture
            private ConcurrentMap<TrophyFish, BareEntryTierData> fish = Concurrent.newMap();

        }

        @Test
        public void bareEntryGrouping_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "count": 5,
                    "blobfish": 5767,
                    "blobfish_bronze": 4044,
                    "blobfish_silver": 1582,
                    "gusher": 100,
                    "gusher_bronze": 80
                }
                """;

            BareEntryGroupingModel model = gson.fromJson(json, BareEntryGroupingModel.class);

            assertThat(model.getCount(), is(5));
            assertThat(model.getEntries(), aMapWithSize(2));
            assertThat(model.getEntries(), hasKey("blobfish"));
            assertThat(model.getEntries().get("blobfish").getTotal(), is(5767));
            assertThat(model.getEntries().get("blobfish").getBronze(), is(4044));
            assertThat(model.getEntries().get("blobfish").getSilver(), is(1582));
            assertThat(model.getEntries().get("gusher").getTotal(), is(100));
            assertThat(model.getEntries().get("gusher").getBronze(), is(80));
        }

        @Test
        public void bareEntryGroupingRoundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "count": 1,
                    "blobfish": 100,
                    "blobfish_bronze": 50,
                    "blobfish_gold": 10
                }
                """;

            BareEntryGroupingModel first = gson.fromJson(json, BareEntryGroupingModel.class);
            String serialized = gson.toJson(first);
            BareEntryGroupingModel second = gson.fromJson(serialized, BareEntryGroupingModel.class);

            assertThat(second.getCount(), is(first.getCount()));
            assertThat(second.getEntries(), aMapWithSize(1));
            assertThat(second.getEntries().get("blobfish").getTotal(), is(100));
            assertThat(second.getEntries().get("blobfish").getBronze(), is(50));
            assertThat(second.getEntries().get("blobfish").getGold(), is(10));
        }

        @Test
        public void bareEntryGroupingWithEnumKey_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "BLOBFISH": 5767,
                    "BLOBFISH_bronze": 4044,
                    "GOLDEN_FISH": 81,
                    "GOLDEN_FISH_diamond": 5
                }
                """;

            BareEntryEnumKeyModel model = gson.fromJson(json, BareEntryEnumKeyModel.class);

            assertThat(model.getFish(), aMapWithSize(2));
            assertThat(model.getFish(), hasKey(TrophyFish.BLOBFISH));
            assertThat(model.getFish().get(TrophyFish.BLOBFISH).getTotal(), is(5767));
            assertThat(model.getFish().get(TrophyFish.BLOBFISH).getBronze(), is(4044));
            assertThat(model.getFish().get(TrophyFish.GOLDEN_FISH).getTotal(), is(81));
            assertThat(model.getFish().get(TrophyFish.GOLDEN_FISH).getDiamond(), is(5));
        }

        @Test
        public void bareEntryGroupingMixed_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "count": 0,
                    "blobfish_bronze": 50,
                    "gusher": 100,
                    "gusher_silver": 20
                }
                """;

            BareEntryGroupingModel model = gson.fromJson(json, BareEntryGroupingModel.class);

            assertThat(model.getEntries(), aMapWithSize(2));
            assertThat(model.getEntries().get("blobfish").getTotal(), is(0));
            assertThat(model.getEntries().get("blobfish").getBronze(), is(50));
            assertThat(model.getEntries().get("gusher").getTotal(), is(100));
            assertThat(model.getEntries().get("gusher").getSilver(), is(20));
        }

    }

    // ──── SplitTypeAdapterFactory ────

    @Nested
    class SplitTests {

        enum Animal { CAT, DOG, FISH }
        enum Color { RED, BLUE, GREEN }

        @Getter
        @NoArgsConstructor
        static class SplitPairOptionalModel {

            private String name;
            @Split("/")
            @SerializedName("combo")
            private PairOptional<Animal, Color> combo = PairOptional.empty();

        }

        @Getter
        @NoArgsConstructor
        static class SplitPairModel {

            private String id;
            @Split(":")
            private Pair<String, Integer> range;

        }

        @Test
        public void readSplit_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "test",
                    "combo": "cat/red"
                }
                """;

            SplitPairOptionalModel model = gson.fromJson(json, SplitPairOptionalModel.class);

            assertThat(model.getName(), is("test"));
            assertThat(model.getCombo().isPresent(), is(true));
            assertThat(model.getCombo().getLeft(), is(Animal.CAT));
            assertThat(model.getCombo().getRight(), is(Color.RED));
        }

        @Test
        public void readSplitMissing_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "test"
                }
                """;

            SplitPairOptionalModel model = gson.fromJson(json, SplitPairOptionalModel.class);

            assertThat(model.getName(), is("test"));
            assertThat(model.getCombo().isEmpty(), is(true));
        }

        @Test
        public void readSplitNull_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "test",
                    "combo": null
                }
                """;

            SplitPairOptionalModel model = gson.fromJson(json, SplitPairOptionalModel.class);

            assertThat(model.getName(), is("test"));
            assertThat(model.getCombo().isEmpty(), is(true));
        }

        @Test
        public void writeSplit_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "test",
                    "combo": "cat/red"
                }
                """;

            SplitPairOptionalModel model = gson.fromJson(json, SplitPairOptionalModel.class);
            String output = gson.toJson(model);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            assertThat(result.get("name").getAsString(), is("test"));
            assertThat(result.get("combo").getAsString(), is("CAT/RED"));
        }

        @Test
        public void splitRoundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "name": "rt",
                    "combo": "DOG/BLUE"
                }
                """;

            SplitPairOptionalModel first = gson.fromJson(json, SplitPairOptionalModel.class);
            String serialized = gson.toJson(first);
            SplitPairOptionalModel second = gson.fromJson(serialized, SplitPairOptionalModel.class);

            assertThat(second.getName(), is(first.getName()));
            assertThat(second.getCombo().isPresent(), is(true));
            assertThat(second.getCombo().getLeft(), is(Animal.DOG));
            assertThat(second.getCombo().getRight(), is(Color.BLUE));
        }

        @Test
        public void splitPair_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "id": "test",
                    "range": "hello:42"
                }
                """;

            SplitPairModel model = gson.fromJson(json, SplitPairModel.class);

            assertThat(model.getId(), is("test"));
            assertThat(model.getRange(), notNullValue());
            assertThat(model.getRange().getLeft(), is("hello"));
            assertThat(model.getRange().getRight(), is(42));
        }

    }

    // ──── PostInitTypeAdapterFactory ────

    @Nested
    class PostInitTests {

        @Getter
        @NoArgsConstructor
        static class PostInitModel implements PostInit {

            private String firstName;
            private String lastName;
            private transient String fullName;

            @Override
            public void postInit() {
                this.fullName = this.firstName + " " + this.lastName;
            }

        }

        @Getter
        @NoArgsConstructor
        static class PlainModel {

            private String key;
            private int value;

        }

        @Getter
        @NoArgsConstructor
        static class FailingPostInitModel implements PostInit {

            private String data;
            private transient boolean postInitCalled;

            @Override
            public void postInit() {
                this.postInitCalled = true;
                throw new RuntimeException("Intentional failure");
            }

        }

        @Test
        public void postInitCalledOnDeserialize_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "firstName": "John",
                    "lastName": "Doe"
                }
                """;

            PostInitModel model = gson.fromJson(json, PostInitModel.class);

            assertThat(model.getFirstName(), is("John"));
            assertThat(model.getLastName(), is("Doe"));
            assertThat(model.getFullName(), is("John Doe"));
        }

        @Test
        public void postInitNotCalledOnSerialize_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "firstName": "Jane",
                    "lastName": "Doe"
                }
                """;

            PostInitModel model = gson.fromJson(json, PostInitModel.class);
            assertThat(model.getFullName(), is("Jane Doe"));

            // Transient field should not appear in output
            String output = gson.toJson(model);
            JsonObject result = gson.fromJson(output, JsonObject.class);

            assertThat(result.get("firstName").getAsString(), is("Jane"));
            assertThat(result.get("lastName").getAsString(), is("Doe"));
            assertThat(result.has("fullName"), is(false));
        }

        @Test
        public void nonPostInitTypeUnaffected_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "key": "test",
                    "value": 42
                }
                """;

            PlainModel model = gson.fromJson(json, PlainModel.class);

            assertThat(model.getKey(), is("test"));
            assertThat(model.getValue(), is(42));
        }

        @Test
        public void postInitExceptionSwallowed_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "data": "some data"
                }
                """;

            // Should not throw despite postInit failing
            FailingPostInitModel model = gson.fromJson(json, FailingPostInitModel.class);

            assertThat(model.getData(), is("some data"));
            assertThat(model.isPostInitCalled(), is(true));
        }

        @Test
        public void roundTrip_ok() {
            Gson gson = SimplifiedApi.getGson();

            String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Smith"
                }
                """;

            PostInitModel first = gson.fromJson(json, PostInitModel.class);
            String serialized = gson.toJson(first);
            PostInitModel second = gson.fromJson(serialized, PostInitModel.class);

            assertThat(second.getFirstName(), is(first.getFirstName()));
            assertThat(second.getLastName(), is(first.getLastName()));
            assertThat(second.getFullName(), is("Alice Smith"));
        }

    }

}
