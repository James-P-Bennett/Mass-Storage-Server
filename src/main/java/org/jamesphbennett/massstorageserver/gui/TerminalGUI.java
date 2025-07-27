package org.jamesphbennett.massstorageserver.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.storage.StoredItem;

import java.util.*;

public class TerminalGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location terminalLocation;
    private final String networkId;
    private final Inventory inventory;
    private final Map<Integer, StoredItem> slotToStoredItem = new HashMap<>();

    private List<StoredItem> allItems = new ArrayList<>();
    private List<StoredItem> filteredItems = new ArrayList<>();
    private int currentPage = 0;
    private final int itemsPerPage = 36; // 4 rows of 9 slots

    // Search functionality
    private String currentSearchTerm = null;
    private boolean isSearchActive = false;

    public TerminalGUI(MassStorageServer plugin, Location terminalLocation, String networkId) {
        this.plugin = plugin;
        this.terminalLocation = terminalLocation;
        this.networkId = networkId;

        // Create inventory - 6 rows (54 slots)
        this.inventory = Bukkit.createInventory(null, 54, ChatColor.GREEN + "MSS Terminal");

        // Check for saved search term for this terminal location
        String savedSearchTerm = plugin.getGUIManager().getTerminalSearchTerm(terminalLocation);
        if (savedSearchTerm != null && !savedSearchTerm.isEmpty()) {
            this.currentSearchTerm = savedSearchTerm;
            this.isSearchActive = true;
            plugin.getLogger().info("Loaded saved search term '" + savedSearchTerm + "' for terminal at " + terminalLocation);
        }

        setupGUI();
        loadItems();
    }

    private void setupGUI() {
        // Fill bottom two rows with background
        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.setDisplayName(" ");
        background.setItemMeta(backgroundMeta);

        // Fill bottom two rows (slots 36-53)
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, background);
        }

        // Add navigation and info items
        updateNavigationItems();

        // Add title item
        ItemStack title = new ItemStack(Material.CRAFTER);
        ItemMeta titleMeta = title.getItemMeta();
        titleMeta.setDisplayName(ChatColor.GREEN + "MSS Terminal");
        List<String> titleLore = new ArrayList<>();
        titleLore.add(ChatColor.GRAY + "Access your stored items");
        titleLore.add(ChatColor.GRAY + "Network: " + networkId);
        titleMeta.setLore(titleLore);
        title.setItemMeta(titleMeta);
        inventory.setItem(40, title); // Bottom row center

        // Add search button
        updateSearchButton();
    }

    private void updateSearchButton() {
        ItemStack searchButton = new ItemStack(Material.NAME_TAG);
        ItemMeta searchMeta = searchButton.getItemMeta();

        if (isSearchActive && currentSearchTerm != null) {
            // Search is active - glowing button
            searchMeta.setDisplayName(ChatColor.GOLD + "Search: " + ChatColor.YELLOW + currentSearchTerm);
            List<String> searchLore = new ArrayList<>();
            searchLore.add(ChatColor.GREEN + "Search is active!");
            searchLore.add(ChatColor.GRAY + "Showing items matching: " + ChatColor.WHITE + currentSearchTerm);
            searchLore.add(ChatColor.GRAY + "Results: " + filteredItems.size() + " item types");
            searchLore.add("");
            searchLore.add(ChatColor.YELLOW + "Right-click to clear search");
            searchLore.add(ChatColor.YELLOW + "Left-click to search again");
            searchMeta.setLore(searchLore);

            // Add glowing effect
            searchMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            searchMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            // No search active - normal button
            searchMeta.setDisplayName(ChatColor.AQUA + "Search Items");
            List<String> searchLore = new ArrayList<>();
            searchLore.add(ChatColor.GRAY + "Click to search for specific items");
            searchLore.add(ChatColor.GRAY + "Type part of the item name");
            searchLore.add("");
            searchLore.add(ChatColor.YELLOW + "Left-click to start search");
            searchMeta.setLore(searchLore);
        }

        searchButton.setItemMeta(searchMeta);
        inventory.setItem(36, searchButton); // Bottom left corner
    }

    private void updateNavigationItems() {
        int maxPages = getMaxPages();

        // Previous page button
        ItemStack prevPage = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prevPage.getItemMeta();
        prevMeta.setDisplayName(ChatColor.YELLOW + "Previous Page");
        List<String> prevLore = new ArrayList<>();
        prevLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + maxPages);
        if (currentPage > 0) {
            prevLore.add(ChatColor.GREEN + "Click to go to previous page");
        } else {
            prevLore.add(ChatColor.RED + "Already on first page");
        }
        prevMeta.setLore(prevLore);
        prevPage.setItemMeta(prevMeta);
        inventory.setItem(45, prevPage);

        // Next page button
        ItemStack nextPage = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = nextPage.getItemMeta();
        nextMeta.setDisplayName(ChatColor.YELLOW + "Next Page");
        List<String> nextLore = new ArrayList<>();
        nextLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + maxPages);
        if (currentPage < maxPages - 1) {
            nextLore.add(ChatColor.GREEN + "Click to go to next page");
        } else {
            nextLore.add(ChatColor.RED + "Already on last page");
        }
        nextMeta.setLore(nextLore);
        nextPage.setItemMeta(nextMeta);
        inventory.setItem(53, nextPage);

        // Info item
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(ChatColor.AQUA + "Storage Info");
        List<String> infoLore = new ArrayList<>();

        if (isSearchActive) {
            infoLore.add(ChatColor.YELLOW + "Search Results: " + filteredItems.size() + " types");
            infoLore.add(ChatColor.GRAY + "Total Item Types: " + allItems.size());
        } else {
            infoLore.add(ChatColor.GRAY + "Total Item Types: " + allItems.size());
        }

        infoLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + maxPages);

        // Calculate total items stored
        List<StoredItem> displayItems = isSearchActive ? filteredItems : allItems;
        long totalItems = displayItems.stream().mapToLong(StoredItem::getQuantity).sum();
        infoLore.add(ChatColor.GRAY + (isSearchActive ? "Filtered" : "Total") + " Items: " + totalItems);

        infoLore.add("");
        infoLore.add(ChatColor.YELLOW + "Left Click: Take full stack to cursor");
        infoLore.add(ChatColor.YELLOW + "Right Click: Take half stack to cursor");
        infoLore.add(ChatColor.YELLOW + "Shift Click: Take full stack to inventory");
        infoLore.add("");
        infoLore.add(ChatColor.AQUA + "Click with items on cursor to store them");
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private int getMaxPages() {
        List<StoredItem> displayItems = isSearchActive ? filteredItems : allItems;
        return Math.max(1, (int) Math.ceil((double) displayItems.size() / itemsPerPage));
    }

    private void loadItems() {
        try {
            // Get all items from network storage
            allItems = plugin.getStorageManager().getNetworkItems(networkId);

            // Sort alphabetically by item type name (will be re-sorted if search is active)
            allItems.sort(Comparator.comparing(item -> item.getItemStack().getType().name()));

            // Apply search filter if active
            applySearchFilter();

            updateDisplayedItems();

            plugin.getLogger().info("Loaded " + allItems.size() + " total items" +
                    (isSearchActive ? ", filtered to " + filteredItems.size() + " results" : ""));
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading terminal items: " + e.getMessage());
        }
    }

    private void applySearchFilter() {
        filteredItems.clear();

        if (!isSearchActive || currentSearchTerm == null || currentSearchTerm.trim().isEmpty()) {
            isSearchActive = false;
            return;
        }

        String searchLower = currentSearchTerm.toLowerCase().trim();
        plugin.getLogger().info("Applying search filter for term: '" + searchLower + "'");

        // Create a list to hold items with their relevance scores
        List<ScoredItem> scoredItems = new ArrayList<>();

        for (StoredItem item : allItems) {
            String itemName = item.getItemStack().getType().name().toLowerCase().replace("_", " ");
            String displayName = "";

            // Check display name if it exists
            if (item.getItemStack().hasItemMeta() && item.getItemStack().getItemMeta().hasDisplayName()) {
                displayName = ChatColor.stripColor(item.getItemStack().getItemMeta().getDisplayName()).toLowerCase();
            }

            // Calculate relevance score
            int score = calculateRelevanceScore(itemName, displayName, searchLower);

            if (score > 0) {
                scoredItems.add(new ScoredItem(item, score));
            }
        }

        // Sort by relevance score (higher score = more relevant = appears first)
        scoredItems.sort((a, b) -> Integer.compare(b.score, a.score));

        // Extract the sorted items
        for (ScoredItem scoredItem : scoredItems) {
            filteredItems.add(scoredItem.item);
        }

        plugin.getLogger().info("Search for '" + currentSearchTerm + "' found " + filteredItems.size() + " matching items out of " + allItems.size() + " total");
    }

    /**
     * Calculate relevance score for search matching
     * Higher score = more relevant
     */
    private int calculateRelevanceScore(String itemName, String displayName, String searchTerm) {
        int score = 0;

        // Exact match gets highest score
        if (itemName.equals(searchTerm) || displayName.equals(searchTerm)) {
            score += 1000;
        }

        // Starts with search term gets high score
        if (itemName.startsWith(searchTerm) || displayName.startsWith(searchTerm)) {
            score += 500;
        }

        // Contains search term gets medium score
        if (itemName.contains(searchTerm) || displayName.contains(searchTerm)) {
            score += 100;
        }

        // Word boundary matches get bonus points
        String[] itemWords = itemName.split(" ");
        String[] displayWords = displayName.split(" ");

        for (String word : itemWords) {
            if (word.startsWith(searchTerm)) {
                score += 200;
            } else if (word.contains(searchTerm)) {
                score += 50;
            }
        }

        for (String word : displayWords) {
            if (word.startsWith(searchTerm)) {
                score += 200;
            } else if (word.contains(searchTerm)) {
                score += 50;
            }
        }

        // Bonus for shorter item names (more specific matches)
        if (score > 0 && itemName.length() < 20) {
            score += 25;
        }

        return score;
    }

    /**
     * Helper class for scored search results
     */
    private static class ScoredItem {
        final StoredItem item;
        final int score;

        ScoredItem(StoredItem item, int score) {
            this.item = item;
            this.score = score;
        }
    }

    private void updateDisplayedItems() {
        // Clear current item display (first 4 rows)
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, null);
        }
        slotToStoredItem.clear();

        // Use filtered items if search is active, otherwise use all items
        List<StoredItem> displayItems = isSearchActive ? filteredItems : allItems;

        // Calculate start and end indices for current page
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, displayItems.size());

        // Display items for current page
        for (int i = startIndex; i < endIndex; i++) {
            StoredItem storedItem = displayItems.get(i);
            int slot = i - startIndex;

            ItemStack displayItem = createDisplayItem(storedItem);
            inventory.setItem(slot, displayItem);
            slotToStoredItem.put(slot, storedItem);
        }

        // Update navigation and search button
        updateNavigationItems();
        updateSearchButton();
    }

    private ItemStack createDisplayItem(StoredItem storedItem) {
        ItemStack displayItem = storedItem.getDisplayStack();
        ItemMeta meta = displayItem.getItemMeta();

        // NEW BEHAVIOR: Display logic based on quantity and max stack size
        int maxStackSize = displayItem.getMaxStackSize();
        if (storedItem.getQuantity() > maxStackSize) {
            // For items > max stack size, show as single item (no number)
            displayItem.setAmount(1);
        } else {
            // For items ≤ max stack size, show actual quantity
            displayItem.setAmount(storedItem.getQuantity());
        }

        // Add quantity information to lore
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.YELLOW + "Stored: " + storedItem.getQuantity());

        // Add interaction hints with UPDATED click behavior
        lore.add("");
        lore.add(ChatColor.GRAY + "Left Click: Take full stack to cursor");
        lore.add(ChatColor.GRAY + "Right Click: Take half stack to cursor");
        lore.add(ChatColor.GRAY + "Shift Click: Take full stack to inventory");

        meta.setLore(lore);
        displayItem.setItemMeta(meta);

        return displayItem;
    }

    /**
     * Set search term and apply filter (used when loading saved search)
     */
    public void setSearch(String searchTerm) {
        plugin.getLogger().info("setSearch called with term: '" + searchTerm + "'");

        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            clearSearch();
            return;
        }

        this.currentSearchTerm = searchTerm.trim();
        this.isSearchActive = true;
        this.currentPage = 0; // Reset to first page

        plugin.getLogger().info("Setting search term '" + currentSearchTerm + "' for terminal, active: " + isSearchActive);

        // Apply the filter
        applySearchFilter();

        // Update display
        updateDisplayedItems();

        plugin.getLogger().info("Search applied: " + filteredItems.size() + " results out of " + allItems.size() + " total items");
    }

    /**
     * Get the current filtered item count (for result display)
     */
    public int getFilteredItemCount() {
        return isSearchActive ? filteredItems.size() : allItems.size();
    }

    /**
     * Start search mode - close GUI and prompt for search term
     */
    public void startSearch(Player player) {
        plugin.getLogger().info("Starting search mode for player " + player.getName());

        // Close the inventory
        player.closeInventory();

        // Send chat message prompt
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== MSS Terminal Search ===");
        player.sendMessage(ChatColor.YELLOW + "Type your search term in chat within 10 seconds.");
        player.sendMessage(ChatColor.GRAY + "Example: 'diamond' or 'diam' to find diamond items");
        player.sendMessage(ChatColor.RED + "Type 'cancel' to cancel the search.");
        player.sendMessage("");

        // Register the player for search input
        plugin.getGUIManager().registerSearchInput(player, this);
    }

    /**
     * REMOVED: applySearch method - now handled by GUIManager
     */

    /**
     * Clear search and show all items
     */
    public void clearSearch() {
        this.currentSearchTerm = null;
        this.isSearchActive = false;
        this.currentPage = 0; // Reset to first page
        this.filteredItems.clear();

        // Also clear from GUI manager's saved state
        plugin.getGUIManager().setTerminalSearchTerm(terminalLocation, null);

        updateDisplayedItems();
        plugin.getLogger().info("Search cleared, showing all items");
    }

    public void open(Player player) {
        player.openInventory(inventory);
        // Register this instance as a listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Refresh the terminal display
     */
    public void refresh() {
        plugin.getLogger().info("Refreshing terminal at " + terminalLocation + " for network " + networkId);
        int itemCountBefore = allItems.size();

        // Store current search state
        String savedSearchTerm = currentSearchTerm;
        boolean wasSearchActive = isSearchActive;

        loadItems();

        // Restore search state after refresh
        if (wasSearchActive && savedSearchTerm != null) {
            setSearch(savedSearchTerm);
        }

        int itemCountAfter = allItems.size();
        plugin.getLogger().info("Terminal refresh complete: " + itemCountBefore + " -> " + itemCountAfter + " item types" +
                (wasSearchActive ? " (search preserved: '" + savedSearchTerm + "')" : ""));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        // Handle search button click
        if (slot == 36) {
            event.setCancelled(true);

            if (event.getClick() == ClickType.RIGHT && isSearchActive) {
                // Right-click: Clear search
                clearSearch();
                player.sendMessage(ChatColor.YELLOW + "Search cleared!");
                return;
            } else if (event.getClick() == ClickType.LEFT) {
                // Left-click: Start new search
                startSearch(player);
                return;
            }
            return;
        }

        // Handle shift-clicks from player inventory for item storage
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) &&
                slot >= inventory.getSize()) {

            // Shift-clicking FROM player inventory TO terminal (to store items)
            ItemStack itemToStore = event.getCurrentItem();

            if (itemToStore != null && !itemToStore.getType().isAir()) {
                event.setCancelled(true);

                // Check if item is allowed to be stored
                if (!plugin.getItemManager().isItemAllowed(itemToStore)) {
                    player.sendMessage(ChatColor.RED + "This item cannot be stored in the network!");
                    return;
                }

                // Store the item
                try {
                    List<ItemStack> toStore = new ArrayList<>();
                    toStore.add(itemToStore.clone());

                    List<ItemStack> remainders = plugin.getStorageManager().storeItems(networkId, toStore);

                    if (remainders.isEmpty()) {
                        // All items stored successfully
                        event.setCurrentItem(null);
                        player.sendMessage(ChatColor.GREEN + "Stored " + itemToStore.getAmount() + " " +
                                itemToStore.getType().name().toLowerCase().replace("_", " "));

                        // Refresh display immediately
                        refresh();
                    } else {
                        // Some items couldn't be stored
                        ItemStack remainder = remainders.get(0);
                        event.setCurrentItem(remainder);
                        int stored = itemToStore.getAmount() - remainder.getAmount();
                        if (stored > 0) {
                            player.sendMessage(ChatColor.YELLOW + "Stored " + stored + " items. " +
                                    remainder.getAmount() + " items couldn't be stored (network full?)");
                            refresh();
                        } else {
                            player.sendMessage(ChatColor.RED + "No space available in the network!");
                        }
                    }
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error storing items: " + e.getMessage());
                    plugin.getLogger().severe("Error storing items: " + e.getMessage());
                }
            }
            return;
        }

        if (slot < 36) {
            // Clicking on item display area
            handleItemClick(event, player, slot);
        } else if (slot < 54) {
            // Clicking on bottom navigation/control area
            event.setCancelled(true);
            handleNavigationClick(event, player, slot);
        }
        // Regular clicks in player inventory are allowed for manual item management
    }

    /**
     * Calculate how much space is available in player inventory for a specific item type
     * WITHOUT modifying the inventory
     */
    private int calculateInventorySpace(Player player, ItemStack item) {
        int totalSpace = 0;

        // Check slots 0-35 (main inventory, excluding hotbar which is 0-8 but we want to include it)
        for (int i = 0; i < 36; i++) {
            ItemStack invItem = player.getInventory().getItem(i);

            if (invItem == null || invItem.getType().isAir()) {
                // Empty slot - can fit a full stack
                totalSpace += item.getMaxStackSize();
            } else if (invItem.isSimilar(item)) {
                // Similar item - can add to existing stack
                int canAdd = invItem.getMaxStackSize() - invItem.getAmount();
                totalSpace += canAdd;
            }
            // Different items take up space but don't contribute to our item's space
        }

        return totalSpace;
    }

    private void handleItemClick(InventoryClickEvent event, Player player, int slot) {
        event.setCancelled(true);

        ItemStack cursorItem = event.getCursor();

        // PRIORITY 1: If player has items on cursor, try to store them
        if (cursorItem != null && !cursorItem.getType().isAir()) {
            plugin.getLogger().info("Player has " + cursorItem.getAmount() + " " + cursorItem.getType() + " on cursor, attempting to store");

            // Check if item can be stored
            if (!plugin.getItemManager().isItemAllowed(cursorItem)) {
                player.sendMessage(ChatColor.RED + "This item cannot be stored in the network!");
                return;
            }

            // Store the cursor item
            try {
                List<ItemStack> toStore = new ArrayList<>();
                toStore.add(cursorItem.clone());

                List<ItemStack> remainders = plugin.getStorageManager().storeItems(networkId, toStore);

                if (remainders.isEmpty()) {
                    // All items stored successfully
                    event.setCursor(null);
                    player.sendMessage(ChatColor.GREEN + "Stored " + cursorItem.getAmount() + " " +
                            cursorItem.getType().name().toLowerCase().replace("_", " "));
                    plugin.getLogger().info("Successfully stored all cursor items");
                } else {
                    // Some items couldn't be stored
                    ItemStack remainder = remainders.get(0);
                    event.setCursor(remainder);
                    int stored = cursorItem.getAmount() - remainder.getAmount();
                    if (stored > 0) {
                        player.sendMessage(ChatColor.YELLOW + "Stored " + stored + " items. " +
                                remainder.getAmount() + " items couldn't be stored (network full?)");
                        plugin.getLogger().info("Partially stored " + stored + "/" + cursorItem.getAmount() + " cursor items");
                    } else {
                        player.sendMessage(ChatColor.RED + "No space available in the network!");
                        plugin.getLogger().warning("Could not store any cursor items - network full");
                    }
                }

                // Refresh the display
                refresh();
                return; // IMPORTANT: Return here, don't continue to retrieval logic

            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Error storing items: " + e.getMessage());
                plugin.getLogger().severe("Error storing cursor items: " + e.getMessage());
                return;
            }
        }

        // PRIORITY 2: If no cursor items, handle retrieval with FIXED BEHAVIOR
        StoredItem storedItem = slotToStoredItem.get(slot);
        if (storedItem == null) {
            plugin.getLogger().info("No stored item found in slot " + slot);
            return;
        }

        ClickType clickType = event.getClick();
        int amountToRetrieve = 0;
        boolean directToInventory = false;

        // Get the max stack size for this item type
        int maxStackSize = storedItem.getItemStack().getMaxStackSize();

        switch (clickType) {
            case LEFT:
                // Take up to max stack size (or less if not available) to cursor
                amountToRetrieve = Math.min(maxStackSize, storedItem.getQuantity());
                directToInventory = false;
                break;
            case RIGHT:
                // Take half of max stack size (or less if not available) to cursor
                int halfStack = Math.max(1, maxStackSize / 2);
                amountToRetrieve = Math.min(halfStack, storedItem.getQuantity());
                directToInventory = false;
                break;
            case SHIFT_LEFT:
                // Take up to max stack size directly to inventory, but respect available quantity
                amountToRetrieve = Math.min(maxStackSize, storedItem.getQuantity());
                directToInventory = true;

                // CRITICAL FIX: Check if player inventory has space BEFORE retrieving
                if (amountToRetrieve > 0) {
                    // Create a test item to check space requirements
                    ItemStack testItem = storedItem.getItemStack().clone();
                    testItem.setAmount(amountToRetrieve);

                    // Calculate available space WITHOUT modifying inventory
                    int spaceAvailable = calculateInventorySpace(player, testItem);

                    // Adjust amount to what actually fits
                    amountToRetrieve = Math.min(amountToRetrieve, spaceAvailable);

                    if (amountToRetrieve <= 0) {
                        player.sendMessage(ChatColor.RED + "Your inventory is full!");
                        plugin.getLogger().info("Cancelled shift-click retrieval - player inventory full");
                        return;
                    } else if (amountToRetrieve < Math.min(maxStackSize, storedItem.getQuantity())) {
                        player.sendMessage(ChatColor.YELLOW + "Only retrieving " + amountToRetrieve + " items due to inventory space");
                    }
                }
                break;
            default:
                plugin.getLogger().info("Unhandled click type: " + clickType);
                return;
        }

        if (amountToRetrieve > 0) {
            plugin.getLogger().info("Retrieving " + amountToRetrieve + " items of type " + storedItem.getItemStack().getType() +
                    " (available: " + storedItem.getQuantity() + ", max stack: " + maxStackSize + ")" +
                    (directToInventory ? " to inventory" : " to cursor"));

            try {
                ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(
                        networkId, storedItem.getItemHash(), amountToRetrieve);

                if (retrievedItem != null) {
                    if (directToInventory) {
                        // Add directly to player inventory
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(retrievedItem);

                        if (!leftover.isEmpty()) {
                            // This should rarely happen since we pre-checked, but handle it
                            plugin.getLogger().warning("Unexpected: " + leftover.size() + " items didn't fit after pre-check!");
                            // Put the items back in storage
                            try {
                                List<ItemStack> putBack = new ArrayList<>();
                                putBack.addAll(leftover.values());
                                plugin.getStorageManager().storeItems(networkId, putBack);

                                // Calculate what was actually added
                                int actuallyAdded = retrievedItem.getAmount() - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                                if (actuallyAdded > 0) {
                                    player.sendMessage(ChatColor.YELLOW + "Retrieved " + actuallyAdded + " items. " +
                                            (retrievedItem.getAmount() - actuallyAdded) + " items returned to storage (inventory full)");
                                } else {
                                    player.sendMessage(ChatColor.RED + "Items returned to storage - inventory full");
                                }
                            } catch (Exception e) {
                                // Last resort - drop the items
                                for (ItemStack item : leftover.values()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                                }
                                player.sendMessage(ChatColor.RED + "Critical error - some items were dropped!");
                            }
                        }
                    } else {
                        // Put on cursor
                        event.setCursor(retrievedItem);
                    }

                    // Refresh the display
                    refresh();

                    player.sendMessage(ChatColor.GREEN + "Retrieved " + amountToRetrieve + " " +
                            retrievedItem.getType().name().toLowerCase().replace("_", " "));
                    plugin.getLogger().info("Successfully retrieved " + amountToRetrieve + " items");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not retrieve items - they may have been taken by another player.");
                    plugin.getLogger().warning("Retrieval returned null - items may have been taken");
                    refresh(); // Refresh to show current state
                }
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Error retrieving items: " + e.getMessage());
                plugin.getLogger().severe("Error retrieving items: " + e.getMessage());
            }
        }
    }

    private void handleNavigationClick(InventoryClickEvent event, Player player, int slot) {
        switch (slot) {
            case 45: // Previous page
                if (currentPage > 0) {
                    currentPage--;
                    updateDisplayedItems();
                    player.sendMessage(ChatColor.YELLOW + "Page " + (currentPage + 1) + "/" + getMaxPages());
                }
                break;
            case 53: // Next page
                if (currentPage < getMaxPages() - 1) {
                    currentPage++;
                    updateDisplayedItems();
                    player.sendMessage(ChatColor.YELLOW + "Page " + (currentPage + 1) + "/" + getMaxPages());
                }
                break;
            case 40: // Title item
            case 49: // Info item
            default:
                // Do nothing for other slots
                break;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        plugin.getLogger().info("Drag event detected with " + event.getRawSlots().size() + " slots affected");

        // Check if dragging into the terminal area
        boolean draggedIntoItemArea = false;
        boolean draggedIntoNavArea = false;

        for (int slot : event.getRawSlots()) {
            if (slot < 36) {
                draggedIntoItemArea = true;
            } else if (slot < 54) {
                draggedIntoNavArea = true;
            }
        }

        if (draggedIntoNavArea) {
            // Dragging into navigation area - always cancel
            event.setCancelled(true);
            plugin.getLogger().info("Cancelled drag - attempted to drag into navigation area");
            return;
        }

        if (draggedIntoItemArea) {
            // Dragging into item display area - try to store the item
            if (event.getWhoClicked() instanceof Player player) {
                ItemStack draggedItem = event.getOldCursor();

                if (draggedItem != null && !draggedItem.getType().isAir()) {
                    plugin.getLogger().info("Player " + player.getName() + " dragging " +
                            draggedItem.getAmount() + " " + draggedItem.getType() + " into terminal");

                    // Check if item can be stored
                    if (!plugin.getItemManager().isItemAllowed(draggedItem)) {
                        event.setCancelled(true);
                        player.sendMessage(ChatColor.RED + "This item cannot be stored in the network!");
                        plugin.getLogger().info("Cancelled drag - item not allowed: " + draggedItem.getType());
                        return;
                    }

                    // Calculate how much is being dragged (drag splits items across slots)
                    int totalDraggedAmount = 0;
                    for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
                        if (entry.getKey() < 36) { // Only count items in terminal area
                            totalDraggedAmount += entry.getValue().getAmount();
                        }
                    }

                    if (totalDraggedAmount > 0) {
                        // Create an item stack with the total dragged amount
                        ItemStack itemToStore = draggedItem.clone();
                        itemToStore.setAmount(totalDraggedAmount);

                        try {
                            List<ItemStack> toStore = new ArrayList<>();
                            toStore.add(itemToStore);

                            plugin.getLogger().info("Attempting to store " + totalDraggedAmount + " " + draggedItem.getType() + " via drag");
                            List<ItemStack> remainders = plugin.getStorageManager().storeItems(networkId, toStore);

                            event.setCancelled(true);

                            if (remainders.isEmpty()) {
                                // All stored - calculate what should remain on cursor
                                int remainingOnCursor = draggedItem.getAmount() - totalDraggedAmount;
                                if (remainingOnCursor > 0) {
                                    ItemStack newCursor = draggedItem.clone();
                                    newCursor.setAmount(remainingOnCursor);
                                    event.setCursor(newCursor);
                                } else {
                                    event.setCursor(null);
                                }

                                player.sendMessage(ChatColor.GREEN + "Stored " + totalDraggedAmount + " " +
                                        draggedItem.getType().name().toLowerCase().replace("_", " "));
                                plugin.getLogger().info("Successfully stored all " + totalDraggedAmount + " dragged items");
                            } else {
                                // Partial storage
                                ItemStack remainder = remainders.get(0);
                                int stored = totalDraggedAmount - remainder.getAmount();

                                // Calculate new cursor amount
                                int newCursorAmount = draggedItem.getAmount() - stored;
                                ItemStack newCursor = draggedItem.clone();
                                newCursor.setAmount(newCursorAmount);
                                event.setCursor(newCursor);

                                if (stored > 0) {
                                    player.sendMessage(ChatColor.YELLOW + "Stored " + stored + " items. " +
                                            remainder.getAmount() + " items couldn't be stored.");
                                    plugin.getLogger().info("Partially stored " + stored + "/" + totalDraggedAmount + " dragged items");
                                } else {
                                    player.sendMessage(ChatColor.RED + "No space available in the network!");
                                    plugin.getLogger().warning("No space available for dragged items");
                                }
                            }

                            // Refresh display
                            refresh();
                            return;
                        } catch (Exception e) {
                            player.sendMessage(ChatColor.RED + "Error storing items: " + e.getMessage());
                            plugin.getLogger().severe("Error storing dragged items: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            event.setCancelled(true);
            return;
        }

        // If we get here, the drag is only in player inventory - allow it
        plugin.getLogger().info("Allowing drag - only affects player inventory");
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Unregister this listener
        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryDragEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        // Remove from GUI manager
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    // Getters for search state (for GUI manager)
    public boolean isSearchActive() {
        return isSearchActive;
    }

    public String getCurrentSearchTerm() {
        return currentSearchTerm;
    }

    public Location getTerminalLocation() {
        return terminalLocation;
    }

    public String getNetworkId() {
        return networkId;
    }
}