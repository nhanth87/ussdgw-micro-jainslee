package et.restlink.ussdgw.persist;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;

/**
 * Startup DB guard (OTA parity): Flyway repair + migrate, then verify required tables/columns.
 * Works for lab file H2 ({@code MODE=PostgreSQL}) and prod PostgreSQL.
 */
@ApplicationScoped
public class UssdSchemaInitializer {

    private static final Logger LOG = LogManager.getLogger(UssdSchemaInitializer.class);

    static final List<String> REQUIRED_TABLES = List.of(
            "ussd_short_code",
            "ussd_tenant",
            "ussd_admin_user",
            "ussd_cdr",
            "ussd_campaign",
            "ussd_campaign_target",
            "ussd_config"
    );

    static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("ussd_short_code", "tenant_id"),
            new RequiredColumn("ussd_short_code", "network_id"),
            new RequiredColumn("ussd_tenant", "http_api_key"),
            new RequiredColumn("ussd_tenant", "network_id"),
            new RequiredColumn("ussd_tenant", "max_tps"),
            new RequiredColumn("ussd_tenant", "http_as_wire_format"),
            new RequiredColumn("ussd_admin_user", "role"),
            new RequiredColumn("ussd_admin_user", "tenant_id"),
            new RequiredColumn("ussd_cdr", "tenant_id"),
            new RequiredColumn("ussd_cdr", "origination_type"),
            new RequiredColumn("ussd_campaign", "tenant_id")
    );

    static final List<String> MIGRATIONS = List.of(
            "V1__ussdgw_baseline.sql",
            "V2__tenant_http_as_wire_format.sql"
    );

    @Inject
    DataSource dataSource;

    @Inject
    Flyway flyway;

    @ConfigProperty(name = "ussd.db.schema-init.enabled", defaultValue = "true")
    boolean enabled;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE + 100) StartupEvent ev) {
        if (!enabled) {
            LOG.info("[ussd-schema] init disabled (ussd.db.schema-init.enabled=false)");
            return;
        }
        ensureSchema();
    }

    public void ensureSchema() {
        try {
            flyway.repair();
        } catch (RuntimeException ex) {
            LOG.debug("[ussd-schema] flyway.repair skipped: {}", ex.getMessage());
        }
        int applied = flyway.migrate().migrationsExecuted;
        if (applied > 0) {
            LOG.info("[ussd-schema] Flyway migrate finished, migrationsExecuted={}", applied);
        } else {
            LOG.debug("[ussd-schema] Flyway migrate — no pending migrations");
        }

        List<String> missingTables = findMissingTables();
        List<String> missingColumns = findMissingColumns();
        if (missingTables.isEmpty() && missingColumns.isEmpty()) {
            LOG.info("[ussd-schema] OK — all {} tables and {} required columns present",
                    REQUIRED_TABLES.size(), REQUIRED_COLUMNS.size());
            return;
        }

        LOG.warn("[ussd-schema] incomplete after Flyway — missing tables {} columns {} — classpath fallback",
                missingTables, missingColumns);
        runClasspathSchemaFallback();

        missingTables = findMissingTables();
        missingColumns = findMissingColumns();
        if (!missingTables.isEmpty() || !missingColumns.isEmpty()) {
            throw new IllegalStateException(
                    "USSD DB schema incomplete after auto-init; missing tables: " + missingTables
                            + ", missing columns: " + missingColumns
                            + ". Check JDBC URL / credentials and db/migration/" + MIGRATIONS);
        }
        LOG.info("[ussd-schema] OK after SQL fallback — schema ready");
    }

    List<String> findMissingTables() {
        Set<String> present = loadPresentTables();
        List<String> missing = new ArrayList<>();
        for (String t : REQUIRED_TABLES) {
            if (!present.contains(t.toLowerCase(Locale.ROOT))) {
                missing.add(t);
            }
        }
        return missing;
    }

    List<String> findMissingColumns() {
        List<String> missing = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String catalog = c.getCatalog();
            String schema = c.getSchema();
            for (RequiredColumn col : REQUIRED_COLUMNS) {
                if (!columnExists(md, catalog, schema, col.table(), col.column())) {
                    missing.add(col.table() + "." + col.column());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect DB columns for USSD schema init", e);
        }
        return missing;
    }

    private static boolean columnExists(
            DatabaseMetaData md, String catalog, String schema, String table, String column)
            throws SQLException {
        if (columnInResult(md, catalog, schema, table, column)) {
            return true;
        }
        if (columnInResult(md, null, null, table, column)) {
            return true;
        }
        String upperTable = table.toUpperCase(Locale.ROOT);
        String upperCol = column.toUpperCase(Locale.ROOT);
        return columnInResult(md, null, null, upperTable, upperCol);
    }

    private static boolean columnInResult(
            DatabaseMetaData md, String catalog, String schema, String table, String column)
            throws SQLException {
        try (ResultSet rs = md.getColumns(catalog, schema, table, column)) {
            return rs.next();
        }
    }

    private Set<String> loadPresentTables() {
        Set<String> names = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String catalog = c.getCatalog();
            String schema = c.getSchema();
            try (ResultSet rs = md.getTables(catalog, schema, "%", new String[] {"TABLE", "BASE TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (names.isEmpty()) {
                try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE", "BASE TABLE"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        if (name != null) {
                            names.add(name.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect DB tables for USSD schema init", e);
        }
        return names;
    }

    private void runClasspathSchemaFallback() {
        try (Connection c = dataSource.getConnection()) {
            applyMigrationLadder(c);
        } catch (SQLException e) {
            throw new IllegalStateException("USSD schema SQL fallback failed: " + e.getMessage(), e);
        }
    }

    static void applyMigrationLadder(Connection c) throws SQLException {
        boolean prev = c.getAutoCommit();
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            for (String migration : MIGRATIONS) {
                for (String stmt : splitSql(readMigration(migration))) {
                    try {
                        st.execute(stmt);
                    } catch (SQLException ex) {
                        LOG.warn("[ussd-schema] fallback skip in {}: {}", migration, ex.getMessage());
                    }
                }
                LOG.info("[ussd-schema] fallback applied {}", migration);
            }
        } finally {
            c.setAutoCommit(prev);
        }
    }

    static String readMigration(String fileName) {
        String resource = "db/migration/" + fileName;
        try (var in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath " + resource + " not found");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    static List<String> splitSql(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cur.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String s = cur.toString().trim();
                if (s.endsWith(";")) {
                    s = s.substring(0, s.length() - 1).trim();
                }
                if (!s.isEmpty()) {
                    out.add(s);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    record RequiredColumn(String table, String column) {
    }
}
