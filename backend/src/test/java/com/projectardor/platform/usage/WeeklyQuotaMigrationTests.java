package com.projectardor.platform.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Keeps the weekly quota policy honest in both directions: an already-applied
 * migration stays byte-identical, and the FREE allowances that actually reach
 * users stay the product numbers rather than whatever a test run needed.
 */
class WeeklyQuotaMigrationTests {
    /** Checksum recorded by every database that already applied V68. */
    private static final int APPLIED_V68_CHECKSUM = -64397967;
    private static final Pattern FREE_WEEKLY_AGENT_CHAT = Pattern.compile("WHEN 'AGENT_CHAT' THEN (\\d+)");
    private static final Pattern FREE_WEEKLY_KNOWLEDGE_SEARCH = Pattern.compile("WHEN 'KNOWLEDGE_SEARCH' THEN (\\d+)");

    @Test void appliedWeeklyQuotaMigrationIsNeverRewritten() throws IOException {
        Path v68 = migrationDirectory().resolve("V68__add_weekly_quota_refresh.sql");
        assertEquals(APPLIED_V68_CHECKSUM, flywayChecksum(v68),
                "V68 already ran on existing databases; rewriting it breaks Flyway validation."
                        + " Put changed values in a new migration instead.");
    }

    @Test void freeWeeklyAllowancesStayAtProductValues() throws IOException {
        List<Path> migrations = weeklyLimitMigrations();
        assertTrue(!migrations.isEmpty(), "At least one migration must seed the weekly limits");
        Path latest = migrations.getLast();
        String sql = Files.readString(latest, StandardCharsets.UTF_8);
        assertEquals(2, firstNumber(FREE_WEEKLY_AGENT_CHAT, sql),
                "FREE keeps two agent chats per week; acceptance runs raise limits in their own database, not in policy");
        assertEquals(5, firstNumber(FREE_WEEKLY_KNOWLEDGE_SEARCH, sql),
                "FREE keeps five knowledge searches per week");
    }

    /** Migrations that assign FREE weekly allowances, ordered by Flyway version. */
    private static List<Path> weeklyLimitMigrations() throws IOException {
        List<Path> matches = new ArrayList<>();
        try (var files = Files.list(migrationDirectory())) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.matches("V\\d+__.*\\.sql")) continue;
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                int freeBlock = sql.indexOf("free_weekly_limit = CASE feature");
                if (freeBlock < 0) continue;
                if (FREE_WEEKLY_AGENT_CHAT.matcher(sql.substring(freeBlock)).find()) matches.add(file);
            }
        }
        matches.sort(Comparator.comparingLong(WeeklyQuotaMigrationTests::versionOf));
        return matches;
    }

    private static long versionOf(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(1, name.indexOf("__")));
    }

    private static int firstNumber(Pattern pattern, String sql) {
        int freeBlock = sql.indexOf("free_weekly_limit = CASE feature");
        assertTrue(freeBlock >= 0, "Migration must contain the FREE weekly allowance block");
        Matcher matcher = pattern.matcher(sql.substring(freeBlock));
        assertTrue(matcher.find(), "No FREE weekly allowance row matched " + pattern);
        return Integer.parseInt(matcher.group(1));
    }

    /** Flyway validates migrations with a CRC32 over each line, without line terminators. */
    private static int flywayChecksum(Path file) throws IOException {
        CRC32 crc32 = new CRC32();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                crc32.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) crc32.getValue();
    }

    private static Path migrationDirectory() {
        Path fromBackend = Path.of("src", "main", "resources", "db", "migration");
        if (Files.isDirectory(fromBackend)) return fromBackend;
        return Path.of("backend", "src", "main", "resources", "db", "migration");
    }
}
