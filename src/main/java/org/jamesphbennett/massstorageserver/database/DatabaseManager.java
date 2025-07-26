package org.jamesphbennett.massstorageserver.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jamesphbennett.massstorageserver.MassStorageServer;

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

        // CRITICAL: Run migration to fix storage constraints
        migrateDatabaseSchema();
    }

    private void initializeDatabase() throws SQLException {
        try {
            // Create plugin data folder if it doesn't exist
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            String databasePath = plugin.getDataFolder().getAbsolutePath() + "/storage.db";

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

            dataSource = new HikariDataSource(config);

            plugin.getLogger().info("Database initialized successfully at: " + databasePath);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database!", e);
            throw new SQLException("Database initialization failed", e);
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
                max_cells INTEGER NOT NULL DEFAULT 27,
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
                CHECK (slot_number >= 0 AND slot_number < 8)
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
                UNIQUE(disk_id, item_hash),
                CHECK (quantity >= 0 AND quantity <= 1024)
            )
            """
        };

        String[] indexCreationQueries = {
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_location ON network_blocks(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_network ON network_blocks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_custom_block_markers_location ON custom_block_markers(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_network ON storage_disks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_crafter ON storage_disks(crafter_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_location ON drive_bay_slots(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_network ON drive_bay_slots(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_disk ON drive_bay_slots(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_disk ON storage_items(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_hash ON storage_items(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_networks_owner ON networks(owner_uuid)"
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
     */
    private void migrateDatabaseSchema() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if we need to migrate by looking for the constraint
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
                    // Step 1: Create new table without the unique constraint
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
                            CHECK (quantity >= 0 AND quantity <= 1024)
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
                    plugin.getLogger().info("Database migration completed successfully - multiple cells per item type now allowed");

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