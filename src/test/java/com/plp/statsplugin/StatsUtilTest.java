package com.plp.statsplugin;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StatsUtil")
class StatsUtilTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a minimal Minecraft stats JSON for a given section + key → value. */
    private static JsonObject statsJson(String section, String key, int value) {
        JsonObject root    = new JsonObject();
        JsonObject stats   = new JsonObject();
        JsonObject sec     = new JsonObject();
        sec.addProperty(key, value);
        stats.add(section, sec);
        root.add("stats", stats);
        return root;
    }

    /** Builds a stats JSON with multiple values in one section. */
    private static JsonObject multiValueSection(String section, String... kvPairs) {
        JsonObject root  = new JsonObject();
        JsonObject stats = new JsonObject();
        JsonObject sec   = new JsonObject();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            sec.addProperty(kvPairs[i], Integer.parseInt(kvPairs[i + 1]));
        }
        stats.add(section, sec);
        root.add("stats", stats);
        return root;
    }

    // =========================================================================

    @Nested
    @DisplayName("getAnyStat()")
    class GetAnyStat {

        @Test
        @DisplayName("returns correct value from minecraft:custom")
        void customSection() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 42);
            assertEquals(42, StatsUtil.getAnyStat(json, "minecraft:jump"));
        }

        @Test
        @DisplayName("returns correct value from minecraft:mined")
        void minedSection() {
            JsonObject json = statsJson("minecraft:mined", "minecraft:stone", 100);
            assertEquals(100, StatsUtil.getAnyStat(json, "minecraft:stone"));
        }

        @Test
        @DisplayName("returns 0 when key is absent in all sections")
        void missingKey() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 5);
            assertEquals(0, StatsUtil.getAnyStat(json, "minecraft:deaths"));
        }

        @Test
        @DisplayName("returns 0 for null root")
        void nullRoot() {
            assertEquals(0, StatsUtil.getAnyStat(null, "minecraft:jump"));
        }

        @Test
        @DisplayName("returns 0 for null/blank key")
        void nullOrBlankKey() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 1);
            assertEquals(0, StatsUtil.getAnyStat(json, null));
            assertEquals(0, StatsUtil.getAnyStat(json, "   "));
        }

        @Test
        @DisplayName("returns 0 when root has no 'stats' object")
        void noStatsObject() {
            JsonObject json = new JsonObject();
            json.addProperty("version", 1);
            assertEquals(0, StatsUtil.getAnyStat(json, "minecraft:jump"));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("getStatInSection()")
    class GetStatInSection {

        @Test
        @DisplayName("reads exact section and key")
        void exactSectionAndKey() {
            JsonObject json = statsJson("minecraft:killed", "minecraft:zombie", 7);
            assertEquals(7, StatsUtil.getStatInSection(json, "minecraft:killed", "minecraft:zombie"));
        }

        @Test
        @DisplayName("returns 0 when section is missing")
        void missingSectionReturnsZero() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 10);
            assertEquals(0, StatsUtil.getStatInSection(json, "minecraft:mined", "minecraft:stone"));
        }

        @Test
        @DisplayName("returns 0 when key is missing inside section")
        void missingKeyReturnsZero() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 10);
            assertEquals(0, StatsUtil.getStatInSection(json, "minecraft:custom", "minecraft:deaths"));
        }

        @Test
        @DisplayName("returns 0 for null section or key")
        void nullArgs() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 3);
            assertEquals(0, StatsUtil.getStatInSection(json, null, "minecraft:jump"));
            assertEquals(0, StatsUtil.getStatInSection(json, "minecraft:custom", null));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("totalSection()")
    class TotalSection {

        @Test
        @DisplayName("sums all values in a section")
        void sumsCorrectly() {
            JsonObject json = multiValueSection("minecraft:mined",
                    "minecraft:stone", "10",
                    "minecraft:dirt",  "5",
                    "minecraft:sand",  "3");
            assertEquals(18, StatsUtil.totalSection(json, "minecraft:mined"));
        }

        @Test
        @DisplayName("returns 0 for missing section")
        void missingSectionReturnsZero() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 1);
            assertEquals(0, StatsUtil.totalSection(json, "minecraft:mined"));
        }

        @Test
        @DisplayName("returns 0 for null section name")
        void nullSection() {
            JsonObject json = statsJson("minecraft:mined", "minecraft:stone", 1);
            assertEquals(0, StatsUtil.totalSection(json, null));
        }

        @Test
        @DisplayName("returns 0 for empty section")
        void emptySection() {
            JsonObject root  = new JsonObject();
            JsonObject stats = new JsonObject();
            stats.add("minecraft:mined", new JsonObject());
            root.add("stats", stats);
            assertEquals(0, StatsUtil.totalSection(root, "minecraft:mined"));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("sectionHasStatKey()")
    class SectionHasStatKey {

        @Test
        @DisplayName("returns true when key exists")
        void keyExists() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 1);
            assertTrue(StatsUtil.sectionHasStatKey(json, "minecraft:custom", "minecraft:jump"));
        }

        @Test
        @DisplayName("returns false when key is absent")
        void keyAbsent() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 1);
            assertFalse(StatsUtil.sectionHasStatKey(json, "minecraft:custom", "minecraft:deaths"));
        }

        @Test
        @DisplayName("returns false for null args")
        void nullArgs() {
            JsonObject json = statsJson("minecraft:custom", "minecraft:jump", 1);
            assertFalse(StatsUtil.sectionHasStatKey(json, null, "minecraft:jump"));
            assertFalse(StatsUtil.sectionHasStatKey(json, "minecraft:custom", null));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("getAvailableStatSections()")
    class GetAvailableStatSections {

        @Test
        @DisplayName("returns all present section names")
        void multipleSections() {
            JsonObject root  = new JsonObject();
            JsonObject stats = new JsonObject();
            stats.add("minecraft:custom",  new JsonObject());
            stats.add("minecraft:mined",   new JsonObject());
            root.add("stats", stats);

            Set<String> sections = StatsUtil.getAvailableStatSections(root);
            assertTrue(sections.contains("minecraft:custom"));
            assertTrue(sections.contains("minecraft:mined"));
            assertEquals(2, sections.size());
        }

        @Test
        @DisplayName("returns empty set for null root")
        void nullRoot() {
            assertTrue(StatsUtil.getAvailableStatSections(null).isEmpty());
        }

        @Test
        @DisplayName("non-object entries are excluded")
        void nonObjectEntriesExcluded() {
            JsonObject root  = new JsonObject();
            JsonObject stats = new JsonObject();
            stats.addProperty("not_a_section", "value"); // primitive, not an object
            stats.add("minecraft:custom", new JsonObject());
            root.add("stats", stats);

            Set<String> sections = StatsUtil.getAvailableStatSections(root);
            assertEquals(Set.of("minecraft:custom"), sections);
        }
    }
}
