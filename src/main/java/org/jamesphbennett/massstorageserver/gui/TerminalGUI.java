package org.jamesphbennett.massstorageserver.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
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
    private int currentPage = 0;
    private final int itemsPerPage = 36; // 4 rows of 9 slots

    public TerminalGUI(MassStorageServer plugin, Location terminalLocation, String networkId) {
        this.plugin = plugin;
        this.terminalLocation = terminalLocation;
        this.networkId = networkId;

        // Create inventory - 6 rows (54 slots)
        this.inventory = Bukkit.createInventory(null, 54, ChatColor.GREEN + "MSS Terminal");

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
    }

    private void updateNavigationItems() {
        // Previous page button
        ItemStack prevPage = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prevPage.getItemMeta();
        prevMeta.setDisplayName(ChatColor.YELLOW + "Previous Page");
        List<String> prevLore = new ArrayList<>();
        prevLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + getMaxPages());
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
        nextLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + getMaxPages());
        if (currentPage < getMaxPages() - 1) {
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
        infoLore.add(ChatColor.GRAY + "Total Item Types: " + allItems.size());
        infoLore.add(ChatColor.GRAY + "Page: " + (currentPage + 1) + "/" + getMaxPages());

        // Calculate total items stored
        long totalItems = allItems.stream().mapToLong(StoredItem::getQuantity).sum();
        infoLore.add(ChatColor.GRAY + "Total Items: " + totalItems);

        infoLore.add("");
        infoLore.add(ChatColor.YELLOW + "Left Click: Take 64 to cursor");
        infoLore.add(ChatColor.YELLOW + "Right Click: Take 32 to cursor");
        infoLore.add(ChatColor.YELLOW + "Shift Click: Take 64 to inventory");
        infoLore.add("");
        infoLore.add(ChatColor.AQUA + "Click with items on cursor to store them");
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private int getMaxPages() {
        return Math.max(1, (int) Math.ceil((double) allItems.size() / itemsPerPage));
    }

    private void loadItems() {
        try {
            // Get all items from network storage
            allItems = plugin.getStorageManager().getNetworkItems(networkId);

            // Sort alphabetically by item type name
            allItems.sort(Comparator.comparing(item -> item.getItemStack().getType().name()));

            updateDisplayedItems();
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading terminal items: " + e.getMessage());
        }
    }

    private void updateDisplayedItems() {
        // Clear current item display (first 4 rows)
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, null);
        }
        slotToStoredItem.clear();

        // Calculate start and end indices for current page
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, allItems.size());

        // Display items for current page
        for (int i = startIndex; i < endIndex; i++) {
            StoredItem storedItem = allItems.get(i);
            int slot = i - startIndex;

            ItemStack displayItem = createDisplayItem(storedItem);
            inventory.setItem(slot, displayItem);
            slotToStoredItem.put(slot, storedItem);
        }

        // Update navigation
        updateNavigationItems();
    }

    private ItemStack createDisplayItem(StoredItem storedItem) {
        ItemStack displayItem = storedItem.getDisplayStack();
        ItemMeta meta = displayItem.getItemMeta();

        // NEW BEHAVIOR: Display logic based on quantity
        if (storedItem.getQuantity() > 64) {
            // For items > 64, show as single item (no number)
            displayItem.setAmount(1);
        } else {
            // For items ≤ 64, show actual stack size
            displayItem.setAmount(storedItem.getQuantity());
        }

        // Add quantity information to lore
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.YELLOW + "Stored: " + storedItem.getQuantity());

        // Add interaction hints with UPDATED click behavior
        lore.add("");
        lore.add(ChatColor.GRAY + "Left Click: Take 64 to cursor");
        lore.add(ChatColor.GRAY + "Right Click: Take 32 to cursor");
        lore.add(ChatColor.GRAY + "Shift Click: Take 64 to inventory");

        meta.setLore(lore);
        displayItem.setItemMeta(meta);

        return displayItem;
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
        loadItems();
        int itemCountAfter = allItems.size();
        plugin.getLogger().info("Terminal refresh complete: " + itemCountBefore + " -> " + itemCountAfter + " item types");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

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

        // PRIORITY 2: If no cursor items, handle retrieval with NEW BEHAVIOR
        StoredItem storedItem = slotToStoredItem.get(slot);
        if (storedItem == null) {
            plugin.getLogger().info("No stored item found in slot " + slot);
            return;
        }

        ClickType clickType = event.getClick();
        int amountToRetrieve = 0;
        boolean directToInventory = false;

        switch (clickType) {
            case LEFT:
                // Take 64 items (or less if not available) to cursor
                amountToRetrieve = Math.min(64, storedItem.getQuantity());
                directToInventory = false;
                break;
            case RIGHT:
                // Take exactly 32 items (or max available up to 32) to cursor
                amountToRetrieve = Math.min(32, storedItem.getQuantity());
                directToInventory = false;
                break;
            case SHIFT_LEFT:
                // Take 64 items directly to inventory (like shift-clicking from chest)
                amountToRetrieve = Math.min(64, storedItem.getQuantity());
                directToInventory = true;
                break;
            default:
                plugin.getLogger().info("Unhandled click type: " + clickType);
                return;
        }

        if (amountToRetrieve > 0) {
            plugin.getLogger().info("Retrieving " + amountToRetrieve + " items of type " + storedItem.getItemStack().getType() +
                    (directToInventory ? " to inventory" : " to cursor"));

            try {
                ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(
                        networkId, storedItem.getItemHash(), amountToRetrieve);

                if (retrievedItem != null) {
                    if (directToInventory) {
                        // Add directly to player inventory
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(retrievedItem);
                        if (!leftover.isEmpty()) {
                            // Drop leftover items
                            for (ItemStack item : leftover.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), item);
                            }
                            player.sendMessage(ChatColor.YELLOW + "Some items were dropped due to full inventory!");
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
}