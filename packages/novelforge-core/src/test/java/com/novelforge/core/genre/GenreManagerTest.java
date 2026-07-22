package com.novelforge.core.genre;

import com.novelforge.core.models.GenreProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenreManagerTest {

    @Test
    void testSingletonInstance() {
        GenreManager gm1 = GenreManager.getInstance();
        GenreManager gm2 = GenreManager.getInstance();
        assertSame(gm1, gm2, "GenreManager should be singleton");
    }

    @Test
    void testAllBuiltInGenresPresent() {
        GenreManager gm = GenreManager.getInstance();
        String[] keys = gm.listGenreKeys();

        // Should have at least 10 built-in genres
        assertTrue(keys.length >= 10, "Should have at least 10 built-in genres: " + keys.length);

        // Check each built-in key exists
        String[] builtInKeys = {"xuanhuan", "xianxia", "urban", "horror", "romance-zh",
                                "fantasy", "thriller", "romance-en", "scifi", "mystery"};
        for (String key : builtInKeys) {
            GenreProfile profile = gm.getGenre(key);
            assertNotNull(profile, "Built-in genre '" + key + "' should exist");
            assertEquals(key, profile.getKey());
        }
    }

    @Test
    void testXuanhuanGenreDetails() {
        GenreProfile p = GenreManager.getInstance().getGenre("xuanhuan");
        assertEquals("玄幻", p.getLabel());
        assertEquals("zh", p.getLanguage());
        assertNotNull(p.getOutlineTemplate());
        assertTrue(p.getOutlineTemplate().contains("废材"));
        assertNotNull(p.getTropes());
        assertTrue(p.getTropes().length > 0);
        assertNotNull(p.getNamingConvention());
        assertNotNull(p.getPacingRules());
        assertNotNull(p.getUpgradeSystem());
    }

    @Test
    void testXianxiaGenreDetails() {
        GenreProfile p = GenreManager.getInstance().getGenre("xianxia");
        assertEquals("仙侠", p.getLabel());
        assertEquals("zh", p.getLanguage());
        assertNotNull(p.getTropes());
        assertTrue(p.getTropes().length > 0);
    }

    @Test
    void testUrbanGenreDetails() {
        GenreProfile p = GenreManager.getInstance().getGenre("urban");
        assertEquals("都市", p.getLabel());
        assertEquals("zh", p.getLanguage());
    }

    @Test
    void testHorrorGenreDetails() {
        GenreProfile p = GenreManager.getInstance().getGenre("horror");
        assertEquals("恐怖", p.getLabel());
        assertNotNull(p.getAvoidanceList());
        assertTrue(p.getAvoidanceList().length > 0);
    }

    @Test
    void testRomanceZhGenreDetails() {
        GenreProfile p = GenreManager.getInstance().getGenre("romance-zh");
        assertEquals("言情", p.getLabel());
        assertEquals("zh", p.getLanguage());
    }

    @Test
    void testEnglishGenres() {
        GenreProfile fantasy = GenreManager.getInstance().getGenre("fantasy");
        assertEquals("Fantasy", fantasy.getLabel());
        assertEquals("en", fantasy.getLanguage());

        GenreProfile thriller = GenreManager.getInstance().getGenre("thriller");
        assertEquals("Thriller", thriller.getLabel());
        assertEquals("en", thriller.getLanguage());

        GenreProfile romance = GenreManager.getInstance().getGenre("romance-en");
        assertEquals("Romance", romance.getLabel());
        assertEquals("en", romance.getLanguage());

        GenreProfile scifi = GenreManager.getInstance().getGenre("scifi");
        assertEquals("Sci-Fi", scifi.getLabel());
        assertEquals("en", scifi.getLanguage());

        GenreProfile mystery = GenreManager.getInstance().getGenre("mystery");
        assertEquals("Mystery", mystery.getLabel());
        assertEquals("en", mystery.getLanguage());
    }

    @Test
    void testNonExistentGenreReturnsNull() {
        GenreProfile p = GenreManager.getInstance().getGenre("nonexistent");
        assertNull(p);
    }

    @Test
    void testGenreProfileFieldsComplete() {
        // All built-in genres should have essential fields populated
        String[] builtInKeys = {"xuanhuan", "xianxia", "urban", "horror", "romance-zh",
                                "fantasy", "thriller", "romance-en", "scifi", "mystery"};
        for (String key : builtInKeys) {
            GenreProfile p = GenreManager.getInstance().getGenre(key);
            assertNotNull(p.getKey(), key + ": key should not be null");
            assertNotNull(p.getLabel(), key + ": label should not be null");
            assertNotNull(p.getLanguage(), key + ": language should not be null");
            assertNotNull(p.getOutlineTemplate(), key + ": outlineTemplate should not be null");
            assertNotNull(p.getNamingConvention(), key + ": namingConvention should not be null");
            assertNotNull(p.getPacingRules(), key + ": pacingRules should not be null");
        }
    }
}
