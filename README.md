# Mass Storage Server

Mass Storage Server is a lightweight early-beta Minecraft plugin inspired by Applied Energistics 2 (AE2) and Refined Storage. It introduces an expandable item storage system built around craftable, removable drives and an intuitive terminal interface. All components are craftable using vanilla recipes or can be accessed through the `/mss` command.

The plugin uses a fast and efficient SQLite database with HikariCP connection pooling. Each drive is uniquely identified and stores its own item pool, allowing for safe concurrent access from multiple users.

**Note:** This plugin is in active development. Use at your own risk and report any issues via the GitHub issues tracker.

---

## Features

- Expandable storage system using drives and drive bays
- Vanilla-style crafting recipes, no mods required
- Efficient SQLite database powered by HikariCP
- Each drive stores items independently by UUID
- Terminal interface supports multi-user access
- Full configuration options for performance and security

---

## Commands

/mss - All admin related commands

---

## Configuration

The configuration file (`config.yml`) provides control over storage capacity, network limits, database settings, permissions, and logging.

```yaml
# Mass Storage Server Configuration
# Created by James Bennett (Hazmad)
# Network settings
network:
  # Maximum number of blocks allowed in a single network
  max_blocks: 128
  
  # Cooldown between operations in milliseconds (prevents spam)
  operation_cooldown: 100

# Storage Drive 1 settings
storage:
  # Maximum items per storage cell
  max_items_per_cell: 1024
  
  # Default number of cells per storage disk
  default_cells_per_disk: 27
  
  # Number of drive bay slots per drive bay
  drive_bay_slots: 8

  # Item blacklist - Items that cannot be stored in the network
  # Storage disks themselves are hardcoded. Empty shulker boxes and bundles are allowed, ones with contents are blocked.
  # Add additional items here using their Material names ex. "DIAMOND_SWORD" or "massstorageserver:storage_disk"
blacklisted_items:
  - "massstorageserver:storage_cell_1k"


# Database settings
database:
  # Connection pool settings
  connection_pool:
    maximum_pool_size: 10
    minimum_idle: 2
    connection_timeout: 30000
    idle_timeout: 600000
    max_lifetime: 1800000

  # SQLite specific settings
  sqlite:
    journal_mode: "WAL"
    synchronous: "NORMAL"
    busy_timeout: 30000
    cache_size: 10000

# Permission settings
permissions:
  require_use_permission: true
  require_craft_permission: true
  require_admin_permission: true

# Logging settings
# Useful but can clutter up console
logging:
  log_network_operations: false
  log_storage_operations: false
  log_database_operations: false

# Debug settings
debug:
  enabled: false
  verbose: false
```

---

## Crafting Recipes

All items can be crafted using the vanilla recipe book (if permissions allow) or accessed via /mss.

### Storage Server

```
[ Glass ] [ Stone ] [ Glass ]
[ Stone ] [ Chest ] [ Stone ]
[ Glass ] [ Stone ] [ Glass ]
```

### Drive Bay

```
[ Stone ] [ Glass ]   [ Stone ]
[ Glass ] [ Hopper ]  [ Glass ]
[ Stone ] [ Glass ]   [ Stone ]
```

### MSS Terminal

```
[ Stone ]    [ Redstone ]    [ Stone ]
[ Redstone ] [ Glass Pane ]  [ Redstone ]
[ Stone ]    [ Redstone ]    [ Stone ]
```

### Storage Disk

```
[ Iron Ingot ] [ Glass ]   [ Iron Ingot ]
[ Glass ]      [ Diamond ] [ Glass ]
[ Iron Ingot ] [ Glass ]   [ Iron Ingot ]
```

---

## Permissions

| Node                        | Description                                 |
|-----------------------------|---------------------------------------------|
| massstorageserver.use       | Allows use of Mass Storage terminals        |
| massstorageserver.craft     | Allows crafting of storage system items     |
| massstorageserver.admin     | Grants access to admin and debug commands   |

---

## Bug Reporting

If you find bugs or have suggestions, please submit them through the GitHub Issues tab.

---

## Credits

- Developed by: James Bennett (Hazmad)
- Database engine: HikariCP and SQLite
- Inspired by: AE2 and Refined Storage

---

## License

This project is licensed under the MIT License. See the LICENSE file for details.
