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
import org.flywaydb.core.api.output.RepairResult;

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
            "ussd_cdr_session",
            "ussd_campaign",
            "ussd_campaign_target",
            "ussd_config",
            "ussd_app_user",
            "ussd_sip_trunk"
    );

    static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("ussd_short_code", "tenant_id"),
            new RequiredColumn("ussd_short_code", "network_id"),
            new RequiredColumn("ussd_short_code", "mark"),
            new RequiredColumn("ussd_short_code", "app_username"),
            new RequiredColumn("ussd_short_code", "bypass"),
            new RequiredColumn("ussd_short_code", "map2map_gt"),
            new RequiredColumn("ussd_short_code", "reroute_enable"),
            new RequiredColumn("ussd_short_code", "hlr_mode"),
            new RequiredColumn("ussd_short_code", "hop_dest_gt"),
            new RequiredColumn("ussd_short_code", "hop_dest_ssn"),
            new RequiredColumn("ussd_tenant", "http_api_key"),
            new RequiredColumn("ussd_tenant", "network_id"),
            new RequiredColumn("ussd_tenant", "max_tps"),
            new RequiredColumn("ussd_tenant", "http_as_wire_format"),
            new RequiredColumn("ussd_tenant", "sip_trunk_id"),
            new RequiredColumn("ussd_admin_user", "role"),
            new RequiredColumn("ussd_admin_user", "tenant_id"),
            new RequiredColumn("ussd_cdr", "tenant_id"),
            new RequiredColumn("ussd_cdr", "origination_type"),
            new RequiredColumn("ussd_cdr", "gate_ms"),
            new RequiredColumn("ussd_cdr", "observed_ewma_ms"),
            new RequiredColumn("ussd_cdr", "hop_outcome"),
            new RequiredColumn("ussd_cdr", "refuse_reason"),
            new RequiredColumn("ussd_cdr", "as_ussd"),
            new RequiredColumn("ussd_cdr_session", "correlation_id"),
            new RequiredColumn("ussd_cdr_session", "started_at"),
            new RequiredColumn("ussd_cdr_session", "updated_at"),
            new RequiredColumn("ussd_cdr_session", "event_count"),
            new RequiredColumn("ussd_cdr_session", "events_json"),
            new RequiredColumn("ussd_cdr_session", "gate_ms"),
            new RequiredColumn("ussd_cdr_session", "as_ussd"),
            new RequiredColumn("ussd_campaign", "tenant_id"),
            new RequiredColumn("ussd_campaign", "created_by"),
            new RequiredColumn("ussd_campaign", "submitted_at"),
            new RequiredColumn("ussd_campaign", "reviewed_by"),
            new RequiredColumn("ussd_campaign", "review_note"),
            new RequiredColumn("ussd_app_user", "tenant_id"),
            new RequiredColumn("ussd_app_user", "api_key_hash"),
            new RequiredColumn("ussd_sip_trunk", "peer_host"),
            new RequiredColumn("ussd_sip_trunk", "inbound_body")
    );

    static final List<String> MIGRATIONS = List.of(
            "V1__ussdgw_baseline.sql",
            "V2__tenant_http_as_wire_format.sql",
            "V3__short_code_mark.sql",
            "V4__config_value_unicode.sql",
            "V5__cdr_gate_metrics.sql",
            "V6__app_user_routing_campaign.sql",
            "V7__sip_trunk.sql",
            "V8__short_code_app_username_unique.sql",
            "V9__short_code_map2map.sql",
            "V10__short_code_reroute.sql",
            "V11__short_code_hop_dest.sql",
            "V12__cdr_hop_outcome.sql",
            "V13__cdr_session_ledger.sql"
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
        int applied = migrateRepairingOnlyIfNeeded();
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

    /**
     * Migrate first; repair only when Flyway itself refuses. An unconditional {@code repair()}
     * silently rewrites drifted checksums on every boot, which defeats
     * {@code quarkus.flyway.validate-on-migrate=true} — the checksum mismatch that should have
     * stopped the node is erased before anyone sees it.
     */
    private int migrateRepairingOnlyIfNeeded() {
        try {
            return flyway.migrate().migrationsExecuted;
        } catch (RuntimeException first) {
            LOG.warn("[ussd-schema] Flyway migrate rejected the existing history ({}) — "
                            + "running repair once, then retrying",
                    first.getMessage());
            try {
                RepairResult repair = flyway.repair();
                LOG.warn("[ussd-schema] Flyway repair: aligned={} removed={} deleted={} actions={}",
                        size(repair.migrationsAligned), size(repair.migrationsRemoved),
                        size(repair.migrationsDeleted), repair.repairActions);
            } catch (RuntimeException repairFailed) {
                LOG.error("[ussd-schema] Flyway repair failed: {}", repairFailed.getMessage(),
                        repairFailed);
                throw first;
            }
            return flyway.migrate().migrationsExecuted;
        }
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
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
