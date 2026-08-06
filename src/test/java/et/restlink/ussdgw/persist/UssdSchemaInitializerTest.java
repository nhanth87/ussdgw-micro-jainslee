package et.restlink.ussdgw.persist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UssdSchemaInitializerTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z_][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z_][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void splitSqlSkipsCommentsAndSplitsStatements() {
        String sql = """
                -- header
                CREATE TABLE IF NOT EXISTS a (id INT);
                CREATE INDEX IF NOT EXISTS i ON a (id);
                """;
        List<String> parts = UssdSchemaInitializer.splitSql(sql);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).toUpperCase().contains("CREATE TABLE"));
        assertTrue(parts.get(1).toUpperCase().contains("CREATE INDEX"));
    }

    @Test
    void requiredCoreTablesPresent() {
        assertTrue(UssdSchemaInitializer.REQUIRED_TABLES.contains("ussd_short_code"));
        assertTrue(UssdSchemaInitializer.REQUIRED_TABLES.contains("ussd_tenant"));
        assertTrue(UssdSchemaInitializer.REQUIRED_TABLES.contains("ussd_cdr"));
        assertTrue(UssdSchemaInitializer.REQUIRED_TABLES.contains("ussd_config"));
    }

    @Test
    void everyRequiredTableIsCreatedByTheFallbackLadder() {
        Set<String> creatable = new TreeSet<>();
        for (String migration : UssdSchemaInitializer.MIGRATIONS) {
            Matcher m = CREATE_TABLE.matcher(UssdSchemaInitializer.readMigration(migration));
            while (m.find()) {
                creatable.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        for (String required : UssdSchemaInitializer.REQUIRED_TABLES) {
            assertTrue(creatable.contains(required),
                    required + " is required at boot but no migration creates it. Ladder: " + creatable);
        }
    }

    @Test
    void everyRequiredColumnIsCoveredByFallbackLadder() {
        for (UssdSchemaInitializer.RequiredColumn col : UssdSchemaInitializer.REQUIRED_COLUMNS) {
            boolean found = false;
            for (String migration : UssdSchemaInitializer.MIGRATIONS) {
                String sql = UssdSchemaInitializer.readMigration(migration);
                if (createsOrAddsColumn(sql, col.table(), col.column())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found,
                    col.table() + "." + col.column()
                            + " is required but no migration CREATE/ADD COLUMN covers it");
        }
    }

    @Test
    void everyMigrationFileIsRegistered() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            List<String> onDisk = files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
            assertEquals(UssdSchemaInitializer.MIGRATIONS.stream().sorted().toList(), onDisk,
                    "every db/migration/*.sql must be listed in UssdSchemaInitializer.MIGRATIONS");
        }
    }

    private static boolean createsOrAddsColumn(String sql, String table, String column) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String t = table.toLowerCase(Locale.ROOT);
        String c = column.toLowerCase(Locale.ROOT);
        if (lower.contains("create table if not exists " + t)) {
            int idx = lower.indexOf("create table if not exists " + t);
            int end = lower.indexOf(");", idx);
            if (end > idx) {
                String body = lower.substring(idx, end + 2);
                if (body.matches("(?s).*\\b" + Pattern.quote(c) + "\\b.*")) {
                    return true;
                }
            }
        }
        Matcher add = ADD_COLUMN.matcher(sql);
        while (add.find()) {
            if (c.equals(add.group(1).toLowerCase(Locale.ROOT)) && lower.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
