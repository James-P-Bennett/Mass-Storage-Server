package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private List<StoredItem> allItems = new ArrayList<>();
    private final List<StoredItem> filteredItems = new ArrayList<>();
    private int currentPage = 0;
    private final int itemsPerPage = 36; // 4 rows of 9 slots

    // Search functionality
    private String currentSearchTerm = null;
    private boolean isSearchActive = false;

    // Sorting functionality
    private boolean isQuantitySortActive; // false = alphabetical, true = quantity

    public TerminalGUI(MassStorageServer plugin, Location terminalLocation, String networkId) {
        this.plugin = plugin;
        this.terminalLocation = terminalLocation;
        this.networkId = networkId;

        // Create inventory - 6 rows (54 slots)
        this.inventory = Bukkit.createInventory(null, 54, miniMessage.deserialize("<green>MSS Terminal"));

        // Check for saved search term for this terminal location
        String savedSearchTerm = plugin.getGUIManager().getTerminalSearchTerm(terminalLocation);
        if (savedSearchTerm != null && !savedSearchTerm.isEmpty()) {
            this.currentSearchTerm = savedSearchTerm;
            this.isSearchActive = true;
            plugin.getLogger().info("Loaded saved search term '" + savedSearchTerm + "' for terminal at " + terminalLocation);
        }

        // Check for saved sorting preference for this terminal location
        this.isQuantitySortActive = plugin.getGUIManager().getTerminalQuantitySort(terminalLocation);
        if (isQuantitySortActive) {
            plugin.getLogger().info("Loaded quantity sort preference for terminal at " + terminalLocation);
        }

        setupGUI();
        loadItems();
    }

    private void setupGUI() {
        // Fill bottom two rows with background
        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.displayName(Component.text(" "));
        background.setItemMeta(backgroundMeta);

        // Fill bottom two rows (slots 36-53)
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, background);
        }

        // Add navigation and info items
        updateNavigationItems();

        // Add search button
        updateSearchButton();

        // Add sorting button
        updateSortingButton();
    }

    private void updateSearchButton() {
        ItemStack searchButton = new ItemStack(Material.SPYGLASS);
        ItemMeta searchMeta = searchButton.getItemMeta();

        if (isSearchActive && currentSearchTerm != null) {
            // Search is active - glowing button
            searchMeta.displayName(miniMessage.deserialize("<gold>Search: <yellow>" + currentSearchTerm));
            List<Component> searchLore = new ArrayList<>();
            searchLore.add(miniMessage.deserialize("<gray>Showing items matching: <white>" + currentSearchTerm));
            searchLore.add(miniMessage.deserialize("<gray>Results: " + filteredItems.size() + " item types"));
            searchLore.add(Component.empty());
            searchLore.add(miniMessage.deserialize("<yellow>Right-click to clear search"));
            searchLore.add(miniMessage.deserialize("<yellow>Left-click to search again"));
            searchMeta.lore(searchLore);

            // Add glowing effect
            searchMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            searchMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            // No search active - normal button
            searchMeta.displayName(miniMessage.deserialize("<aqua>Search"));
            List<Component> searchLore = new ArrayList<>();
            searchLore.add(Component.empty());
            searchLore.add(miniMessage.deserialize("<yellow>Left-click to open search"));
            searchMeta.lore(searchLore);
        }

        searchButton.setItemMeta(searchMeta);
        inventory.setItem(48, searchButton); // Bottom left corner
    }

    private void updateSortingButton() {
        ItemStack sortButton = new ItemStack(Material.NAME_TAG);
        ItemMeta sortMeta = sortButton.getItemMeta();

        if (isQuantitySortActive) {
            // Quantity sorting is active
            sortMeta.displayName(miniMessage.deserialize("<gold>Sorting: Quantity"));
            List<Component> sortLore = new ArrayList<>();
            sortLore.add(Component.empty());
            sortLore.add(miniMessage.deserialize("<yellow>Click to sort alphabetically."));
            sortMeta.lore(sortLore);

            // Add glowing effect
            sortMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            sortMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            // Alphabetical sorting is active (default)
            sortMeta.displayName(miniMessage.deserialize("<aqua>Sorting: Alphabetically"));
            List<Component> sortLore = new ArrayList<>();
            sortLore.add(Component.empty());
            sortLore.add(miniMessage.deserialize("<yellow>Click to sort by quantity"));
            sortMeta.lore(sortLore);
        }

        sortButton.setItemMeta(sortMeta);
        inventory.setItem(50, sortButton); // Next to search button
    }

    private void updateNavigationItems() {
        int maxPages = getMaxPages();

        // Ensure currentPage is within valid bounds
        if (currentPage < 0) {
            currentPage = 0;
        } else if (currentPage >= maxPages) {
            currentPage = Math.max(0, maxPages - 1);
        }

        // Previous page button
        ItemStack prevPage = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prevPage.getItemMeta();
        prevMeta.displayName(miniMessage.deserialize("<yellow>Previous Page"));
        List<Component> prevLore = new ArrayList<>();
        prevLore.add(miniMessage.deserialize("<gray>Page: " + (currentPage + 1) + "/" + maxPages));
        if (currentPage > 0) {
            prevLore.add(miniMessage.deserialize("<green>Click to go to previous page"));
        } else {
            prevLore.add(miniMessage.deserialize("<red>Already on first page"));
        }
        prevMeta.lore(prevLore);
        prevPage.setItemMeta(prevMeta);
        inventory.setItem(45, prevPage);

        // Next page button
        ItemStack nextPage = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = nextPage.getItemMeta();
        nextMeta.displayName(miniMessage.deserialize("<yellow>Next Page"));
        List<Component> nextLore = new ArrayList<>();
        nextLore.add(miniMessage.deserialize("<gray>Page: " + (currentPage + 1) + "/" + maxPages));
        if (currentPage < maxPages - 1) {
            nextLore.add(miniMessage.deserialize("<green>Click to go to next page"));
        } else {
            nextLore.add(miniMessage.deserialize("<red>Already on last page"));
        }
        nextMeta.lore(nextLore);
        nextPage.setItemMeta(nextMeta);
        inventory.setItem(53, nextPage);

        // Info item with better feedback (DISPLAY ONLY - NO CLICK FUNCTIONALITY)
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(miniMessage.deserialize("<aqua>Storage Info"));
        List<Component> infoLore = new ArrayList<>();

        List<StoredItem> displayItems = isSearchActive ? filteredItems : allItems;

        if (isSearchActive) {
            infoLore.add(miniMessage.deserialize("<yellow>Search Results: " + filteredItems.size() + " types"));
            infoLore.add(miniMessage.deserialize("<gray>Total Item Types: " + allItems.size()));
            infoLore.add(miniMessage.deserialize("<gold>Search: '" + currentSearchTerm + "'"));
        } else {
            infoLore.add(miniMessage.deserialize("<gray>Total Item Types: " + allItems.size()));
        }

        infoLore.add(miniMessage.deserialize("<gray>Page: " + (currentPage + 1) + "/" + maxPages));
        infoLore.add(miniMessage.deserialize("<gray>Sort: " + (isQuantitySortActive ? "By Quantity" : "Alphabetical")));

        // Show items on current page
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, displayItems.size());
        if (!displayItems.isEmpty()) {
            infoLore.add(miniMessage.deserialize("<gray>Showing: " + (startIndex + 1) + "-" + endIndex + " of " + displayItems.size()));
        }

        // Calculate total items stored
        long totalItems = displayItems.stream().mapToLong(StoredItem::quantity).sum();
        infoLore.add(miniMessage.deserialize("<gray>" + (isSearchActive ? "Filtered" : "Total") + " Items: " + String.format("%,d", totalItems)));

        infoLore.add(Component.empty());

        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private int getMaxPages() {
        List<StoredItem> displayItems = isSearchActive ? filteredItems : allItems;
        if (displayItems.isEmpty()) {
            return 1; // Always have at least 1 page, even if empty
        }
        return (int) Math.ceil((double) displayItems.size() / itemsPerPage);
    }

    private void loadItems() {
        try {
            // Get all items from network storage
            allItems = plugin.getStorageManager().getNetworkItems(networkId);

            // Apply sorting based on current sort mode
            applySorting();

            // Apply search filter if active
            applySearchFilter();

            updateDisplayedItems();

            plugin.getLogger().info("Loaded " + allItems.size() + " total items" +
                    (isSearchActive ? ", filtered to " + filteredItems.size() + " results" : "") +
                    ", sorted by " + (isQuantitySortActive ? "quantity" : "alphabetical"));
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading terminal items: " + e.getMessage());
        }
    }

    /**
     * Apply sorting to the items list based on current sort mode
     */
    private void applySorting() {
        if (isQuantitySortActive) {
            // Sort by quantity (descending) - most items first
            allItems.sort((a, b) -> {
                int quantityCompare = Integer.compare(b.quantity(), a.quantity());
                if (quantityCompare != 0) {
                    return quantityCompare;
                }
                // If quantities are equal, fall back to alphabetical
                return a.itemStack().getType().name().compareTo(b.itemStack().getType().name());
            });
            plugin.getLogger().info("Applied quantity sorting (most items first)");
        } else {
            // Sort alphabetically by item type name (default)
            allItems.sort(Comparator.comparing(item -> item.itemStack().getType().name()));
            plugin.getLogger().info("Applied alphabetical sorting");
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
            String itemName = item.itemStack().getType().name().toLowerCase().replace("_", " ");
            String displayName = "";

            // Check display name if it exists
            if (item.itemStack().hasItemMeta() && item.itemStack().getItemMeta().hasDisplayName()) {
                Component displayNameComponent = item.itemStack().getItemMeta().displayName();
                if (displayNameComponent != null) {
                    displayName = LegacyComponentSerializer.legacySection().serialize(displayNameComponent).toLowerCase();
                }
            }

            // Calculate relevance score
            int score = calculateRelevanceScore(itemName, displayName, searchLower);

            if (score > 0) {
                scoredItems.add(new ScoredItem(item, score));
            }
        }

        // Sort by relevance score (higher score = more relevant = appears first)
        scoredItems.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score, a.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            // If scores are equal, use current sort mode as tiebreaker
            if (isQuantitySortActive) {
                int quantityCompare = Integer.compare(b.item.quantity(), a.item.quantity());
                if (quantityCompare != 0) {
                    return quantityCompare;
                }
            }
            // Final fallback to alphabetical
            return a.item.itemStack().getType().name().compareTo(b.item.itemStack().getType().name());
        });

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
        private record ScoredItem(StoredItem item, int score) {
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
        updateSortingButton();
    }

    private ItemStack createDisplayItem(StoredItem storedItem) {
        ItemStack displayItem = storedItem.getDisplayStack();
        ItemMeta meta = displayItem.getItemMeta();

        // NEW BEHAVIOR: Display logic based on quantity and max stack size
        int maxStackSize = displayItem.getMaxStackSize();
        if (storedItem.quantity() > maxStackSize) {
            // For items > max stack size, show as single item (no number)
            displayItem.setAmount(1);
        } else {
            // For items ≤ max stack size, show actual quantity
            displayItem.setAmount(storedItem.quantity());
        }

        // Add quantity information to lore
        List<Component> lore = (meta.hasLore() && meta.lore() != null) ? new ArrayList<>(Objects.requireNonNull(meta.lore())) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(miniMessage.deserialize("<yellow>Stored: " + storedItem.quantity()));

        // Add interaction hints with click behavior
        lore.add(Component.empty());
        lore.add(miniMessage.deserialize("<gray>Left Click: Take full stack to cursor"));
        lore.add(miniMessage.deserialize("<gray>Right Click: Take half stack to cursor"));
        lore.add(miniMessage.deserialize("<gray>Shift Click: Take full stack to inventory"));

        meta.lore(lore);
        displayItem.setItemMeta(meta);

        return displayItem;
    }

    /**
     * Toggle sorting mode between alphabetical and quantity-based
     */
    public void toggleSorting() {
        isQuantitySortActive = !isQuantitySortActive;
        this.currentPage = 0; // Reset to first page

        // Save the sorting preference for this terminal
        plugin.getGUIManager().setTerminalQuantitySort(terminalLocation, isQuantitySortActive);

        plugin.getLogger().info("Toggled sorting to: " + (isQuantitySortActive ? "quantity" : "alphabetical"));

        // Re-apply sorting to all items
        applySorting();

        // Re-apply search filter if active (this respects the new sorting)
        if (isSearchActive) {
            applySearchFilter();
        }

        // Update display
        updateDisplayedItems();
    }

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
     * Start search mode - close GUI and prompt for search term
     */
    public void startSearch(Player player) {
        plugin.getLogger().info("Starting search mode for player " + player.getName());

        // Close the inventory
        player.closeInventory();

        // Send chat message prompt using MiniMessage
        player.sendMessage(Component.empty());
        player.sendMessage(miniMessage.deserialize("<gold><bold>=== MSS Terminal Search ==="));
        player.sendMessage(miniMessage.deserialize("<yellow>Enter your search term."));
        player.sendMessage(miniMessage.deserialize("<red>Type 'cancel' to cancel the search."));
        player.sendMessage(Component.empty());

        // Register the player for search input
        plugin.getGUIManager().registerSearchInput(player, this);
    }

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

        // Store current search state and sorting mode
        String savedSearchTerm = currentSearchTerm;
        boolean wasSearchActive = isSearchActive;
        boolean wasSortingByQuantity = isQuantitySortActive;

        loadItems();

        // Restore search state after refresh
        if (wasSearchActive && savedSearchTerm != null) {
            setSearch(savedSearchTerm);
        }

        // Restore sorting mode
        if (wasSortingByQuantity != isQuantitySortActive) {
            isQuantitySortActive = wasSortingByQuantity;
            applySorting();
            if (isSearchActive) {
                applySearchFilter();
            }
            updateDisplayedItems();
        }

        int itemCountAfter = allItems.size();
        plugin.getLogger().info("Terminal refresh complete: " + itemCountBefore + " -> " + itemCountAfter + " item types" +
                (wasSearchActive ? " (search preserved: '" + savedSearchTerm + "')" : "") +
                " (sorting: " + (isQuantitySortActive ? "quantity" : "alphabetical") + ")");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        // Handle search button click (slot 48)
        if (slot == 48) {
            event.setCancelled(true);

            if (event.getClick() == ClickType.RIGHT && isSearchActive) {
                // Right-click: Clear search
                clearSearch();
                player.sendMessage(miniMessage.deserialize("<yellow>Search cleared!"));
                return;
            } else if (event.getClick() == ClickType.LEFT) {
                // Left-click: Start new search
                startSearch(player);
                return;
            }
            return;
        }

        // Handle sorting button click (slot 50)
        if (slot == 50) {
            event.setCancelled(true);

            toggleSorting();
            String newMode = isQuantitySortActive ? "quantity (most items first)" : "alphabetical (A-Z)";
            player.sendMessage(miniMessage.deserialize("<green>Sorting changed to: " + newMode));
            return;
        }

        // Handle navigation clicks (slots 45, 53) - REMOVED slot 49 (info book)
        if (slot == 45 || slot == 53) {
            event.setCancelled(true);
            handleNavigationClick(player, slot);
            return;
        }

        // Handle clicks on info book (slot 49) - CANCEL BUT DO NOTHING
        if (slot == 49) {
            event.setCancelled(true);
            // Info book is now display-only, no action
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
                if (plugin.getItemManager().isItemAllowed(itemToStore)) {
                    player.sendMessage(miniMessage.deserialize("<red>This item cannot be stored in the network!"));
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
//                        player.sendMessage(miniMessage.deserialize("<green>Stored " + itemToStore.getAmount() + " " +
//                                itemToStore.getType().name().toLowerCase().replace("_", " ")));

                        // Refresh display immediately
                        refresh();
                    } else {
                        // Some items couldn't be stored
                        ItemStack remainder = remainders.getFirst();
                        event.setCurrentItem(remainder);
                        int stored = itemToStore.getAmount() - remainder.getAmount();
                        if (stored > 0) {
                            player.sendMessage(miniMessage.deserialize("<yellow>Stored " + stored + " items. " +
                                    remainder.getAmount() + " items couldn't be stored (network full?)"));
                            refresh();
                        } else {
                            player.sendMessage(miniMessage.deserialize("<red>No space available in the network!"));
                        }
                    }
                } catch (Exception e) {
                    player.sendMessage(miniMessage.deserialize("<red>Error storing items: " + e.getMessage()));
                    plugin.getLogger().severe("Error storing items: " + e.getMessage());
                }
            }
            return;
        }

        // Handle clicks in the item display area (slots 0-35)
        if (slot >= 0 && slot < 36) {
            handleItemClick(event, player, slot);
            return;
        }

        // Handle clicks in the bottom control area (slots 36-53) that aren't already handled
        if (slot >= 36 && slot < 54) {
            // Cancel all clicks in the control area that we haven't specifically handled
            // All remaining slots are background/filler - do nothing silently
            event.setCancelled(true);
        }

        // All other clicks (in player inventory) are allowed for manual item management
        // Don't cancel these clicks
    }

    /**
     * Calculate how much space is available in player inventory for a specific item type
     * WITHOUT modifying the inventory
     */
    private int calculateInventorySpace(Player player, ItemStack item) {
        int totalSpace = 0;

        // Check slots 0-35 (main inventory, excluding hotbar which is 0-8, but we want to include it)
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
        if (!cursorItem.getType().isAir()) {
            plugin.getLogger().info("Player has " + cursorItem.getAmount() + " " + cursorItem.getType() + " on cursor, attempting to store");

            // Check if item can be stored
            if (plugin.getItemManager().isItemAllowed(cursorItem)) {
                player.sendMessage(miniMessage.deserialize("<red>This item cannot be stored in the network!"));
                return;
            }

            // Store the cursor item
            try {
                List<ItemStack> toStore = new ArrayList<>();
                toStore.add(cursorItem.clone());

                List<ItemStack> remainders = plugin.getStorageManager().storeItems(networkId, toStore);

                if (remainders.isEmpty()) {
                    // All items stored successfully
                    event.getView().setCursor(null);
                    plugin.getLogger().info("Successfully stored all cursor items");
                } else {
                    // Some items couldn't be stored
                    ItemStack remainder = remainders.getFirst();
                    event.getView().setCursor(remainder);
                    int stored = cursorItem.getAmount() - remainder.getAmount();
                    if (stored > 0) {
                        player.sendMessage(miniMessage.deserialize("<yellow>Stored " + stored + " items. " +
                                remainder.getAmount() + " items couldn't be stored (network full?)"));
                        plugin.getLogger().info("Partially stored " + stored + "/" + cursorItem.getAmount() + " cursor items");
                    } else {
                        player.sendMessage(miniMessage.deserialize("<red>No space available in the network!"));
                        plugin.getLogger().warning("Could not store any cursor items - network full");
                    }
                }

                // Refresh the display
                refresh();
                return; // IMPORTANT: Return here, don't continue to retrieval logic

            } catch (Exception e) {
                player.sendMessage(miniMessage.deserialize("<red>Error storing items: " + e.getMessage()));
                plugin.getLogger().severe("Error storing cursor items: " + e.getMessage());
                return;
            }
        }

        // If no cursor items, handle retrieval
        StoredItem storedItem = slotToStoredItem.get(slot);
        if (storedItem == null) {
            plugin.getLogger().info("No stored item found in slot " + slot);
            return;
        }

        ClickType clickType = event.getClick();
        int amountToRetrieve;
        boolean directToInventory;

        // Get the max stack size for this item type
        int maxStackSize = storedItem.itemStack().getMaxStackSize();

        switch (clickType) {
            case LEFT:
                // Take up to max stack size (or less if not available) to cursor
                amountToRetrieve = Math.min(maxStackSize, storedItem.quantity());
                directToInventory = false;
                break;
            case RIGHT:
                // Take half of max stack size (or less if not available) to cursor
                int halfStack = Math.max(1, maxStackSize / 2);
                amountToRetrieve = Math.min(halfStack, storedItem.quantity());
                directToInventory = false;
                break;
            case SHIFT_LEFT:
                // Take up to max stack size directly to inventory, but respect available quantity
                amountToRetrieve = Math.min(maxStackSize, storedItem.quantity());
                directToInventory = true;

                // Check if player inventory has space BEFORE retrieving
                if (amountToRetrieve > 0) {
                    // Create a test item to check space requirements
                    ItemStack testItem = storedItem.itemStack().clone();
                    testItem.setAmount(amountToRetrieve);

                    // Calculate available space WITHOUT modifying inventory
                    int spaceAvailable = calculateInventorySpace(player, testItem);

                    // Adjust amount to what actually fits
                    amountToRetrieve = Math.min(amountToRetrieve, spaceAvailable);

                    if (amountToRetrieve <= 0) {
                        player.sendMessage(miniMessage.deserialize("<red>Your inventory is full!"));
                        plugin.getLogger().info("Cancelled shift-click retrieval - player inventory full");
                        return;
                    } else if (amountToRetrieve < Math.min(maxStackSize, storedItem.quantity())) {
                        player.sendMessage(miniMessage.deserialize("<yellow>Only retrieving " + amountToRetrieve + " items due to inventory space"));
                    }
                }
                break;
            default:
                plugin.getLogger().info("Unhandled click type: " + clickType);
                return;
        }

        if (amountToRetrieve > 0) {
            plugin.getLogger().info("Retrieving " + amountToRetrieve + " items of type " + storedItem.itemStack().getType() +
                    " (available: " + storedItem.quantity() + ", max stack: " + maxStackSize + ")" +
                    (directToInventory ? " to inventory" : " to cursor"));

            try {
                ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(
                        networkId, storedItem.itemHash(), amountToRetrieve);

                if (retrievedItem != null) {
                    if (directToInventory) {
                        // Add directly to player inventory
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(retrievedItem);

                        if (!leftover.isEmpty()) {
                            // This should rarely happen since we pre-checked, but handle it
                            plugin.getLogger().warning("Unexpected: " + leftover.size() + " items didn't fit after pre-check!");
                            // Put the items back in storage
                            try {
                                List<ItemStack> putBack = new ArrayList<>(leftover.values());
                                plugin.getStorageManager().storeItems(networkId, putBack);

                                // Calculate what was actually added
                                int actuallyAdded = retrievedItem.getAmount() - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                                if (actuallyAdded > 0) {
                                    player.sendMessage(miniMessage.deserialize("<yellow>Retrieved " + actuallyAdded + " items. " +
                                            (retrievedItem.getAmount() - actuallyAdded) + " items returned to storage (inventory full)"));
                                } else {
                                    player.sendMessage(miniMessage.deserialize("<red>Items returned to storage - inventory full"));
                                }
                            } catch (Exception e) {
                                // Last resort - drop the items
                                for (ItemStack item : leftover.values()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                                }
                                player.sendMessage(miniMessage.deserialize("<red>Critical error - some items were dropped!"));
                            }
                        }
                    } else {
                        // Put on cursor
                        event.getView().setCursor(retrievedItem);
                    }

                    // Refresh the display
                    refresh();

                    plugin.getLogger().info("Successfully retrieved " + amountToRetrieve + " items");
                } else {
                    player.sendMessage(miniMessage.deserialize("<red>Could not retrieve items - they may have been taken by another player."));
                    plugin.getLogger().warning("Retrieval returned null - items may have been taken");
                    refresh(); // Refresh to show current state
                }
            } catch (Exception e) {
                player.sendMessage(miniMessage.deserialize("<red>Error retrieving items: " + e.getMessage()));
                plugin.getLogger().severe("Error retrieving items: " + e.getMessage());
            }
        }
    }

    private void handleNavigationClick(Player player, int slot) {
        plugin.getLogger().info("Navigation click detected in slot " + slot + " by player " + player.getName());

        switch (slot) {
            case 45: // Previous page
                plugin.getLogger().info("Previous page clicked, current page: " + currentPage);
                if (currentPage > 0) {
                    currentPage--;
                    updateDisplayedItems();
                    player.sendMessage(miniMessage.deserialize("<yellow>Page " + (currentPage + 1) + "/" + getMaxPages()));
                    plugin.getLogger().info("Moved to page " + (currentPage + 1));
                } else {
                    player.sendMessage(miniMessage.deserialize("<red>Already on the first page!"));
                    plugin.getLogger().info("Already on first page");
                }
                break;

            case 53: // Next page
                plugin.getLogger().info("Next page clicked, current page: " + currentPage);
                int maxPages = getMaxPages();
                if (currentPage < maxPages - 1) {
                    currentPage++;
                    updateDisplayedItems();
                    player.sendMessage(miniMessage.deserialize("<yellow>Page " + (currentPage + 1) + "/" + maxPages));
                    plugin.getLogger().info("Moved to page " + (currentPage + 1));
                } else {
                    player.sendMessage(miniMessage.deserialize("<red>Already on the last page!"));
                    plugin.getLogger().info("Already on last page");
                }
                break;

            default:
                plugin.getLogger().info("Unhandled navigation slot: " + slot);
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

                if (!draggedItem.getType().isAir()) {
                    plugin.getLogger().info("Player " + player.getName() + " dragging " +
                            draggedItem.getAmount() + " " + draggedItem.getType() + " into terminal");

                    // Check if item can be stored
                    if (plugin.getItemManager().isItemAllowed(draggedItem)) {
                        event.setCancelled(true);
                        player.sendMessage(miniMessage.deserialize("<red>This item cannot be stored in the network!"));
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
                                    event.getView().setCursor(newCursor);
                                } else {
                                    event.getView().setCursor(null);
                                }

                            } else {
                                // Partial storage
                                ItemStack remainder = remainders.getFirst();
                                int stored = totalDraggedAmount - remainder.getAmount();

                                // Calculate new cursor amount
                                int newCursorAmount = draggedItem.getAmount() - stored;
                                ItemStack newCursor = draggedItem.clone();
                                newCursor.setAmount(newCursorAmount);
                                event.getView().setCursor(newCursor);

                                if (stored > 0) {
                                    player.sendMessage(miniMessage.deserialize("<yellow>Stored " + stored + " items. " +
                                            remainder.getAmount() + " items couldn't be stored."));
                                    plugin.getLogger().info("Partially stored " + stored + "/" + totalDraggedAmount + " dragged items");
                                } else {
                                    player.sendMessage(miniMessage.deserialize("<red>No space available in the network!"));
                                    plugin.getLogger().warning("No space available for dragged items");
                                }
                            }

                            // Refresh display
                            refresh();
                            return;
                        } catch (Exception e) {
                            player.sendMessage(miniMessage.deserialize("<red>Error storing items: " + e.getMessage()));
                            plugin.getLogger().severe("Error storing dragged items: " + e.getMessage());
                            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
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

    public Location getTerminalLocation() {
        return terminalLocation;
    }

    public String getNetworkId() {
        return networkId;
    }

}