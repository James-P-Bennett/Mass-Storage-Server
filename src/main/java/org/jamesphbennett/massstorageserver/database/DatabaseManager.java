package org.jamesphbennett.massstorageserver.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final MassStorageServer plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(MassStorageServer plugin) throws SQLException {
        this.plugin = plugin;
        initializeDatabase();
        createTables();

        // Run migration to fix storage constraints and update cell counts
        migrateDatabaseSchema();
    }

    private void initializeDatabase() throws SQLException {
        try {
            // Create plugin data folder if it doesn't exist
            if (!plugin.getDataFolder().exists()) {
                if (!plugin.getDataFolder().mkdirs()) {
                    throw new RuntimeException("Failed to create plugin data directory: " + plugin.getDataFolder().getAbsolutePath());
                }
            }

            String databasePath = plugin.getDataFolder().getAbsolutePath() + "/storage.db";

            HikariConfig config = getHikariConfig(databasePath);

            dataSource = new HikariDataSource(config);

            plugin.getLogger().info("Database initialized successfully at: " + databasePath);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database!", e);
            throw new SQLException("Database initialization failed", e);
        }
    }

    private static @NotNull HikariConfig getHikariConfig(String databasePath) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setDriverClassName("org.sqlite.JDBC");

        // Connection pool settings (use config values once ConfigManager is available)
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // SQLite specific settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "30000");
        return config;
    }

    /**
     * Migrate database to add tier support if needed
     */
    private void migrateTierSupport() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if tier column exists
            boolean needsTierMigration = false;

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(storage_disks)")) {
                boolean hasTierColumn = false;
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    if ("tier".equals(columnName)) {
                        hasTierColumn = true;
                        break;
                    }
                }

                if (!hasTierColumn) {
                    needsTierMigration = true;
                    plugin.getLogger().info("Database migration needed - adding tier support to storage_disks");
                }
            }

            if (needsTierMigration) {
                conn.setAutoCommit(false);

                try {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE storage_disks ADD COLUMN tier TEXT DEFAULT '1k'");
                    }

                    // Update existing disks to 1k tier (safe default)
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("UPDATE storage_disks SET tier = '1k' WHERE tier IS NULL");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added tier support to storage_disks table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate tier support", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * CRITICAL FIX: Update all storage disks to have 64 max_cells instead of old values
     */
    private void migrateCellCounts() throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Update all storage disks to have 64 max_cells (new standard)
                try (var stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET max_cells = ? WHERE max_cells != ?")) {
                    stmt.setInt(1, 64); // New standard
                    stmt.setInt(2, 64); // Only update if not already 64
                    int updatedCount = stmt.executeUpdate();

                    if (updatedCount > 0) {
                        plugin.getLogger().info("Updated " + updatedCount + " storage disks to have 64 max_cells");
                    }
                }

                conn.commit();
                plugin.getLogger().info("Cell count migration completed successfully");

            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("Failed to migrate cell counts", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }


    /**
     * Migrate exporter_filters table to include slot_target column for furnace slot routing
     */
    private void migrateSlotTargeting() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if slot_target column exists in exporter_filters
            boolean needsMigration = false;

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(exporter_filters)")) {
                boolean hasSlotTargetColumn = false;
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    if ("slot_target".equals(columnName)) {
                        hasSlotTargetColumn = true;
                        break;
                    }
                }

                if (!hasSlotTargetColumn) {
                    needsMigration = true;
                    plugin.getLogger().info("Database migration needed - adding slot_target column to exporter_filters");
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Add slot_target column with default value 'generic'
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE exporter_filters ADD COLUMN slot_target TEXT DEFAULT 'generic'");
                    }

                    // Update existing filters to 'generic' (safe default for backward compatibility)
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("UPDATE exporter_filters SET slot_target = 'generic' WHERE slot_target IS NULL");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added slot_target column to exporter_filters table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate slot targeting support", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Migrate exporter_filters table to include item_data column
     */
    private void migrateExporterFilters() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if item_data column exists in exporter_filters
            boolean needsMigration = false;

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(exporter_filters)")) {
                boolean hasItemDataColumn = false;
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    if ("item_data".equals(columnName)) {
                        hasItemDataColumn = true;
                        break;
                    }
                }

                if (!hasItemDataColumn) {
                    needsMigration = true;
                    plugin.getLogger().info("Database migration needed - adding item_data column to exporter_filters");
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Add item_data column
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE exporter_filters ADD COLUMN item_data TEXT");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added item_data column to exporter_filters table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate exporter_filters table", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    private void createTables() throws SQLException {
        String[] tableCreationQueries = {
                // Networks table
                """
            CREATE TABLE IF NOT EXISTS networks (
                network_id TEXT PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_accessed TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """,

                // Network blocks table
                """
            CREATE TABLE IF NOT EXISTS network_blocks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                network_id TEXT NOT NULL,
                world_name TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                block_type TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                UNIQUE(world_name, x, y, z)
            )
            """,

                // Custom block markers table - tracks which blocks are our custom items
                """
            CREATE TABLE IF NOT EXISTS custom_block_markers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world_name TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                block_type TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(world_name, x, y, z)
            )
            """,

                // Storage disks table
                """
            CREATE TABLE IF NOT EXISTS storage_disks (
                disk_id TEXT PRIMARY KEY,
                crafter_uuid TEXT NOT NULL,
                crafter_name TEXT NOT NULL,
                network_id TEXT,
                tier TEXT DEFAULT '1k',
                max_cells INTEGER NOT NULL DEFAULT 64,
                used_cells INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE SET NULL
            )
            """,

                // Drive bay slots table
                """    
            CREATE TABLE IF NOT EXISTS drive_bay_slots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                network_id TEXT NOT NULL,
                world_name TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                slot_number INTEGER NOT NULL,
                disk_id TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE SET NULL,
                UNIQUE(world_name, x, y, z, slot_number),
                CHECK (slot_number >= 0 AND slot_number < 7)
            )
            """,

                // Storage items table
                """
                CREATE TABLE IF NOT EXISTS storage_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    disk_id TEXT NOT NULL,
                    item_hash TEXT NOT NULL,
                    item_data TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    max_stack_size INTEGER NOT NULL DEFAULT 64,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE CASCADE,
                    CHECK (quantity >= 0 AND quantity <= 8128)
                )
                """,

                // Exporters table
                """
                CREATE TABLE IF NOT EXISTS exporters (
                    exporter_id TEXT PRIMARY KEY,
                    network_id TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    last_export TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                    UNIQUE(world_name, x, y, z)
                )
                """,

                // Exporter filters table
                """
                CREATE TABLE IF NOT EXISTS exporter_filters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    exporter_id TEXT NOT NULL,
                    item_hash TEXT NOT NULL,
                    item_data TEXT,
                    filter_type TEXT NOT NULL DEFAULT 'whitelist',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (exporter_id) REFERENCES exporters(exporter_id) ON DELETE CASCADE,
                    UNIQUE(exporter_id, item_hash, filter_type)
                )
                """,

                // Importers table
                """
                CREATE TABLE IF NOT EXISTS importers (
                    importer_id TEXT PRIMARY KEY,
                    network_id TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    last_import TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                    UNIQUE(world_name, x, y, z)
                )
                """,

                // Importer filters table
                """
                CREATE TABLE IF NOT EXISTS importer_filters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    importer_id TEXT NOT NULL,
                    item_hash TEXT NOT NULL,
                    item_data TEXT,
                    filter_type TEXT NOT NULL DEFAULT 'whitelist',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (importer_id) REFERENCES importers(importer_id) ON DELETE CASCADE,
                    UNIQUE(importer_id, item_hash, filter_type)
                )
                """,

                // Security terminals table
                """
                CREATE TABLE IF NOT EXISTS security_terminals (
                    terminal_id TEXT PRIMARY KEY,
                    world_name TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    owner_name TEXT NOT NULL,
                    network_id TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE SET NULL,
                    UNIQUE(world_name, x, y, z)
                )
                """,

                // Security terminal trusted players table
                """
                CREATE TABLE IF NOT EXISTS security_terminal_players (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    terminal_id TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    drive_bay_access BOOLEAN NOT NULL DEFAULT false,
                    block_modification_access BOOLEAN NOT NULL DEFAULT false,
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (terminal_id) REFERENCES security_terminals(terminal_id) ON DELETE CASCADE,
                    UNIQUE(terminal_id, player_uuid)
                )
                """
        };

        String[] indexCreationQueries = {
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_location ON network_blocks(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_network ON network_blocks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_custom_block_markers_location ON custom_block_markers(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_network ON storage_disks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_crafter ON storage_disks(crafter_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_tier ON storage_disks(tier)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_location ON drive_bay_slots(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_network ON drive_bay_slots(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_disk ON drive_bay_slots(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_disk ON storage_items(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_hash ON storage_items(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_networks_owner ON networks(owner_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_location ON exporters(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_network ON exporters(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_enabled ON exporters(enabled)",
                "CREATE INDEX IF NOT EXISTS idx_exporter_filters_exporter ON exporter_filters(exporter_id)",
                "CREATE INDEX IF NOT EXISTS idx_exporter_filters_hash ON exporter_filters(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_importers_location ON importers(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_importers_network ON importers(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_importers_enabled ON importers(enabled)",
                "CREATE INDEX IF NOT EXISTS idx_importer_filters_importer ON importer_filters(importer_id)",
                "CREATE INDEX IF NOT EXISTS idx_importer_filters_hash ON importer_filters(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_location ON security_terminals(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_owner ON security_terminals(owner_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_network ON security_terminals(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminal_players_terminal ON security_terminal_players(terminal_id)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminal_players_uuid ON security_terminal_players(player_uuid)"
        };

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Create tables
                for (String query : tableCreationQueries) {
                    stmt.execute(query);
                }

                // Create indexes
                for (String query : indexCreationQueries) {
                    stmt.execute(query);
                }

                conn.commit();
                plugin.getLogger().info("Database tables and indexes created successfully!");

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Get a connection from the pool
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database connection pool is not available");
        }
        return dataSource.getConnection();
    }

    /**
     * Migrate database to remove the unique constraint that prevents multiple cells of same item type
     * AND update cell counts to 64
     */
    private void migrateDatabaseSchema() throws SQLException {
        try (Connection conn = getConnection()) {
            // First run tier support migration
            migrateTierSupport();

            // Then run cell count migration
            migrateCellCounts();

            // Run exporter filters migration
            migrateExporterFilters();

            // Run slot targeting migration
            migrateSlotTargeting();

            // Check if we need to migrate storage_items table constraint
            boolean needsMigration = false;

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='storage_items'")) {
                if (rs.next()) {
                    String tableSql = rs.getString("sql");
                    if (tableSql.contains("UNIQUE(disk_id, item_hash)")) {
                        needsMigration = true;
                        plugin.getLogger().info("Database migration needed - removing unique constraint on storage_items");
                    }
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Step 1: Create new table without the unique constraint and with higher quantity limit
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("""
                        CREATE TABLE storage_items_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            disk_id TEXT NOT NULL,
                            item_hash TEXT NOT NULL,
                            item_data TEXT NOT NULL,
                            quantity INTEGER NOT NULL DEFAULT 0,
                            max_stack_size INTEGER NOT NULL DEFAULT 64,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE CASCADE,
                            CHECK (quantity >= 0 AND quantity <= 8128)
                        )
                        """);
                    }

                    // Step 2: Copy data from old table to new table
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("""
                        INSERT INTO storage_items_new
                        (id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at)
                        SELECT id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at
                        FROM storage_items
                        """);
                    }

                    // Step 3: Drop old table
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("DROP TABLE storage_items");
                    }

                    // Step 4: Rename new table
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE storage_items_new RENAME TO storage_items");
                    }

                    // Step 5: Recreate indexes
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_storage_items_disk ON storage_items(disk_id)");
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_storage_items_hash ON storage_items(item_hash)");
                    }

                    conn.commit();
                    plugin.getLogger().info("Database migration completed successfully - multiple cells per item type now allowed with higher quantity limits");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Database migration failed", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Execute a database transaction
     */
    public void executeTransaction(DatabaseTransaction transaction) throws SQLException {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            transaction.execute(conn);

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(Level.WARNING, "Failed to rollback transaction", rollbackEx);
                }
            }
            throw new SQLException("Transaction failed", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException closeEx) {
                    plugin.getLogger().log(Level.WARNING, "Failed to close connection", closeEx);
                }
            }
        }
    }

    /**
     * Execute a simple update query
     */
    public int executeUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }

            return stmt.executeUpdate();
        }
    }

    /**
     * Shutdown the database connection pool
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shutdown successfully!");
        }
    }

    /**
     * Functional interface for database transactions
     */
    @FunctionalInterface
    public interface DatabaseTransaction {
        void execute(Connection connection) throws Exception;
    }
}