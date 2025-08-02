# Mass Storage Server

Mass Storage Server is a lightweight early-beta Minecraft plugin inspired by Applied Energistics 2 (AE2) and Refined Storage. It introduces an expandable item storage system built around craftable, removable disks and an intuitive terminal interface. All components are craftable using vanilla recipes or can be accessed through the `/mss` command.

The plugin uses a fast and efficient SQLite database with HikariCP connection pooling. Each disk is uniquely identified and stores its own item pool, allowing for safe concurrent access from multiple users.

**Note:** This plugin is in active development. Use at your own risk and report any issues via the GitHub issues tracker.

---

## Key Features

### **Network-Based Storage System**
- **Storage Server**: Central hub that connects all network components
- **Drive Bays**: House up to 7 storage disks each with hot-swappable functionality
- **Terminals**: Access your items with advanced search and sorting capabilities
- **Network Cables**: Extend your network across long distances

### **Multi-Tier Storage Disks**
- **1K Disks**: 8,128 items total (127 items per cell, 64 cells)
- **4K Disks**: 32,512 items total (508 items per cell, 64 cells)  
- **16K Disks**: 130,048 items total (2,032 items per cell, 64 cells)
- **64K Disks**: 520,192 items total (8,128 items per cell, 64 cells)

### **Advanced Terminal Features**
- **Smart Search**: Fuzzy search with relevance scoring
- **Dynamic Sorting**: Alphabetical or quantity-based sorting
- **Per-Terminal Memory**: Search terms and settings persist per terminal location
- **Batch Operations**: Shift-click for inventory transfers, drag-and-drop support
- **Real-time Updates**: Live inventory synchronization across all terminals

### **Comprehensive Crafting System**
- **Tiered Components**: Craft disk platters for each storage tier
- **Vanilla Integration**: All recipes use vanilla materials
- **Custom Recipe Engine**: Supports both vanilla and component-based recipes
- **Recipe Book Integration**: All recipes appear in the vanilla recipe book

### Requirements
- **Server**: Spigot 1.21+
- **Java**: OpenJDK 21 or higher

### Setup
1. Download the latest release from [GitHub Releases](https://github.com/jamesphbennett/massstorageserver/releases)
2. Place the `.jar` file in your server's `plugins` folder
3. Restart your server

### Getting Started
1. **Craft a Storage Server** - The heart of your network
2. **Add Drive Bays** - Place adjacent to the Storage Server
3. **Connect Terminals** - Place adjacent to create access points
4. **Craft Storage Disks** - Start with 1K disks and upgrade as needed

### Network Expansion
- Use **Network Cables** to extend your network beyond adjacent blocks
- Maximum network size: 128 blocks + 800 cables
- Add multiple Drive Bays and Terminals as needed

### Commands
| Command | Description | Permission |
|---------|-------------|------------|
| `/mss help` | Show help menu | Default |
| `/mss give <item> [player]` | Give MSS items | `massstorageserver.admin` |
| `/mss recovery <disk_id>` | Recover lost storage disk | `massstorageserver.recovery` |
| `/mss info` | Show plugin statistics | `massstorageserver.admin` |
| `/mss recipes` | List all recipes | `massstorageserver.admin` |
| `/mss reload [config\|recipes\|all]` | Reload configurations | `massstorageserver.admin` |

### Permissions
| Permission | Description | Default |
|------------|-------------|---------|
| `massstorageserver.use` | Use terminals and drive bays | `true` |
| `massstorageserver.craft` | Craft MSS items | `true` |
| `massstorageserver.admin` | Admin commands | `op` |
| `massstorageserver.recovery` | Recovery commands | `op` |

### config.yml
```
# Mass Storage Server Configuration
# Created by James Bennett (Hazmad)

# Network settings
network:
  # Maximum number of blocks allowed in a single network
  max_blocks: 128

  # Maximum number of network cables allowed in a single network
  max_cables: 800

  # Cooldown between operations in milliseconds (prevents spam)
  operation_cooldown: 100

  # NOTE: All storage disks now have exactly 64 cells (hardcoded)
  # NOTE: Items per cell are tier-specific (hardcoded):
  #   1K Disk: 127 items per cell  (8,128 total capacity)
  #   4K Disk: 508 items per cell  (32,512 total capacity)
  #   16K Disk: 2,032 items per cell (130,048 total capacity)
  #   64K Disk: 8,128 items per cell (520,192 total capacity)

# Item blacklist - Items that cannot be stored in the network
# Storage disks are always blocked (hardcoded for safety)
# Empty shulker boxes and bundles are allowed, ones with contents are blocked
# Add any items you want to prevent from being stored using their Material names
# Examples: "DIAMOND_SWORD", "NETHERITE_PICKAXE", "CHEST", "BARREL"
# Full list of materials: https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/Material.html
blacklisted_items:
# Example: Uncomment the lines below to block these items
# - "CHEST"
# - "TRAPPED_CHEST"
# - "BARREL"
# - "HOPPER"
# - "DISPENSER"
# - "DROPPER"
# - "ENDER_CHEST"
# - "DIAMOND_SWORD"

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

## recipes.yml
```
# Mass Storage Server - Recipe Configuration
# This file defines all craftable recipes for the plugin
# Edit this file to customize recipes or disable them entirely

# CUSTOM INGREDIENT NOTATION:
# For MSS custom components, use the format: "mss:component_type:tier"
# Examples:
# - "mss:disk_platter:1k" = 1K Disk Platter component
# - "mss:disk_platter:4k" = 4K Disk Platter component
# - "mss:storage_disk_housing" = Storage Disk Housing component

# Global recipe settings
settings:
  # Whether recipes should be registered at all
  enabled: true

  # Whether to show recipe unlock messages to players
  show_unlock_messages: false

# Recipe definitions
# Each recipe has:
# - enabled: whether this specific recipe is active
# - result: the item that will be crafted
# - shape: the 3x3 crafting pattern (use spaces for empty slots)
# - ingredients: mapping of letters to materials (vanilla) or custom components (mss:type:tier)

recipes:
  storage_server:
    enabled: true
    result:
      item: "storage_server"
      amount: 1
    shape:
      - "NRN"
      - "RAR"
      - "NRN"
    ingredients:
      N: "NETHERITE_INGOT"
      R: "REDSTONE"
      A: "AMETHYST_SHARD"
    description: "The core of the Mass Storage Network"

  drive_bay:
    enabled: true
    result:
      item: "drive_bay"
      amount: 1
    shape:
      - "BOB"
      - "CCC"
      - "BOB"
    ingredients:
      B: "COPPER_BLOCK"
      C: "CHEST"
      O: "CRYING_OBSIDIAN"
    description: "Holds up to 7 storage disks"

  mss_terminal:
    enabled: true
    result:
      item: "mss_terminal"
      amount: 1
    shape:
      - "TTT"
      - "QEQ"
      - "DRD"
    ingredients:
      T: "TINTED_GLASS"
      Q: "QUARTZ"
      E: "EMERALD"
      D: "DIAMOND"
      R: "REDSTONE"
    description: "Access items stored in the network"

  network_cable:
    enabled: true
    result:
      item: "network_cable"
      amount: 4
    shape:
      - "GGG"
      - "CRC"
      - "GGG"
    ingredients:
      G: "GLASS_PANE"
      C: "COPPER_INGOT"
      R: "REDSTONE"
    description: "Connects network components over distance"

  # ==================== COMPONENT RECIPES ====================

  disk_platter_1k:
    enabled: true
    result:
      item: "disk_platter_1k"
      amount: 1
    shape:
      - "RGR"
      - "GRG"
      - "RGR"
    ingredients:
      R: "REDSTONE"
      G: "GOLD_INGOT"
    description: "1K tier disk platter component"

  disk_platter_4k:
    enabled: true
    result:
      item: "disk_platter_4k"
      amount: 1
    shape:
      - "BGB"
      - "111"
      - "ERE"
    ingredients:
      B: "BLAZE_ROD"
      G: "GOLD_INGOT"
      "1": "mss:disk_platter:1k"  # CUSTOM: 1K disk platter component
      E: "ENDER_PEARL"
      R: "RESIN_BRICK"
    description: "4K tier disk platter component"

  disk_platter_16k:
    enabled: true
    result:
      item: "disk_platter_16k"
      amount: 1
    shape:
      - "BDB"
      - "444"
      - "PRP"
    ingredients:
      B: "BREEZE_ROD"
      D: "DIAMOND"
      "4": "mss:disk_platter:4k"  # CUSTOM: 4K disk platter component
      P: "ENDER_PEARL"
      R: "RESIN_BRICK"
    description: "16K tier disk platter component"

  disk_platter_64k:
    enabled: true
    result:
      item: "disk_platter_64k"
      amount: 1
    shape:
      - "SES"
      - "666"
      - "PRP"
    ingredients:
      S: "SHULKER_SHELL"
      E: "ENDER_EYE"
      "6": "mss:disk_platter:16k"  # CUSTOM: 16K disk platter component
      P: "ENDER_PEARL"
      R: "RESIN_BRICK"
    description: "64K tier disk platter component"

  storage_disk_housing:
    enabled: true
    result:
      item: "storage_disk_housing"
      amount: 1
    shape:
      - "TTT"
      - "I I"
      - "IWI"
    ingredients:
      T: "TINTED_GLASS"
      I: "IRON_INGOT"
      W: "WIND_CHARGE"
    description: "Housing component for storage disks"

  # ==================== STORAGE DISK RECIPES ====================

  storage_disk_1k:
    enabled: true
    result:
      item: "storage_disk_1k"
      amount: 1
    shape:
      - "TTT"
      - "I1I"
      - "IWI"
    ingredients:
      T: "TINTED_GLASS"
      I: "IRON_INGOT"
      "1": "mss:disk_platter:1k"  # CUSTOM: 1K disk platter component
      W: "WIND_CHARGE"
    description: "Basic storage disk - 1K tier"

  storage_disk_4k:
    enabled: true
    result:
      item: "storage_disk_4k"
      amount: 1
    shape:
      - "TTT"
      - "I4I"
      - "IWI"
    ingredients:
      T: "TINTED_GLASS"
      I: "IRON_INGOT"
      "4": "mss:disk_platter:4k"  # CUSTOM: 4K disk platter component
      W: "WIND_CHARGE"
    description: "Advanced storage disk - 4K tier"

  storage_disk_16k:
    enabled: true
    result:
      item: "storage_disk_16k"
      amount: 1
    shape:
      - "TTT"
      - "I6I"
      - "IWI"
    ingredients:
      T: "TINTED_GLASS"
      I: "IRON_INGOT"
      "6": "mss:disk_platter:16k"  # CUSTOM: 16K disk platter component
      W: "WIND_CHARGE"
    description: "High-capacity storage disk - 16K tier"

  storage_disk_64k:
    enabled: true
    result:
      item: "storage_disk_64k"
      amount: 1
    shape:
      - "TTT"
      - "I6I"
      - "IWI"
    ingredients:
      T: "TINTED_GLASS"
      I: "IRON_INGOT"
      "6": "mss:disk_platter:64k"  # CUSTOM: 64K disk platter component
      W: "WIND_CHARGE"
    description: "Maximum-capacity storage disk - 64K tier"
```

## Advanced Features

### Network Recovery System
- **Automatic Restoration**: Drive bay contents automatically restore when networks are rebuilt
- **Orphaned Data Protection**: Items remain safe even when networks are broken
- **Disk Recovery**: Use `/mss recovery <disk_id>` to recover lost disks

### Performance Optimizations
- **HikariCP Connection Pooling**: Efficient database operations
- **Smart Caching**: Reduced database queries with intelligent caching
- **Concurrent Safety**: Thread-safe network operations with proper locking

### Explosion & Griefing Protection
- **TNT Protection**: Blocks drop safely when destroyed by explosions
- **Piston Prevention**: Pistons cannot move or break MSS blocks
- **Drive Bay Safety**: Contents are automatically dropped when drive bays are destroyed
