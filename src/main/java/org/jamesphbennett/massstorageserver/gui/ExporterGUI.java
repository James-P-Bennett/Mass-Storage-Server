package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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
import org.jamesphbennett.massstorageserver.managers.ExporterManager;
import org.jamesphbennett.massstorageserver.storage.StoredItem;

import java.util.*;

public class ExporterGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location exporterLocation;
    private final String exporterId;
    private final String networkId;
    private final Inventory inventory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Filter slots (18 slots for filters)
    private final Map<Integer, ItemStack> slotToFilterItem = new HashMap<>();
    private final List<ItemStack> currentFilterItems = new ArrayList<>();

    public ExporterGUI(MassStorageServer plugin, Location exporterLocation, String exporterId, String networkId) {
        this.plugin = plugin;
        this.exporterLocation = exporterLocation;
        this.exporterId = exporterId;
        this.networkId = networkId;

        // Create inventory - 5 rows (45 slots)
        this.inventory = Bukkit.createInventory(null, 45, legacySerialize("<dark_purple>Exporter Configuration"));

        loadCurrentFilters();
        setupGUI();
    }

    private void loadCurrentFilters() {
        currentFilterItems.clear();
        currentFilterItems.addAll(plugin.getExporterManager().getExporterFilterItems(exporterId));
    }

    private void setupGUI() {
        // Clear inventory first
        inventory.clear();
        slotToFilterItem.clear();

        // Top section (rows 0-1): Filter slots (18 slots) - no placeholders, just empty
        displayFilterSlots();

        // Divider row (row 2): Glass panes
        ItemStack divider = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta dividerMeta = divider.getItemMeta();
        dividerMeta.setDisplayName(" ");
        divider.setItemMeta(dividerMeta);
        for (int i = 18; i < 27; i++) {
            inventory.setItem(i, divider);
        }

        // Bottom section (rows 3-4): Control buttons with glass pane fillers
        setupControlButtons();
    }

    private void displayFilterSlots() {
        // Display current filters - no placeholders for empty slots
        for (int i = 0; i < Math.min(18, currentFilterItems.size()); i++) {
            ItemStack filterItem = currentFilterItems.get(i);
            if (filterItem != null) {
                ItemStack displayItem = filterItem.clone();

                // Always display exactly 1 item in filter slots regardless of original stack size
                displayItem.setAmount(1);

                ItemMeta meta = displayItem.getItemMeta();

                List<String> lore = new ArrayList<>();
                lore.add(legacySerialize("<gray>Filter Item"));
                lore.add("");
                lore.add(legacySerialize("<yellow>Click to remove from filter"));
                meta.setLore(lore);

                // No artificial glowing effect - let naturally enchanted items glow on their own

                displayItem.setItemMeta(meta);
                inventory.setItem(i, displayItem);
                slotToFilterItem.put(i, filterItem);
            }
        }

        // Leave empty filter slots actually empty (no glass panes)
    }

    private void setupControlButtons() {
        ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
        boolean isEnabled = exporterData != null && exporterData.enabled;

        // Fill all control area slots with glass panes first
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 27; i < 45; i++) {
            inventory.setItem(i, filler);
        }

        // Status indicator (slot 29)
        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.setDisplayName(legacySerialize(isEnabled ? "<green>Status: Enabled" : "<gray>Status: Disabled"));

        List<String> statusLore = new ArrayList<>();
        if (!isEnabled && currentFilterItems.isEmpty()) {
            statusLore.add(legacySerialize("<red>Add items to filter to enable"));
        } else {
            statusLore.add(legacySerialize("<gray>Exporter is " + (isEnabled ? "actively exporting" : "inactive")));
        }

        // Add connection status
        Container target = getTargetContainer();
        if (target != null) {
            statusLore.add("");
            statusLore.add(legacySerialize("<aqua>Connected to: " + target.getBlock().getType().name()));
        } else {
            statusLore.add("");
            statusLore.add(legacySerialize("<red>No valid container connected"));
        }

        statusLore.add("");
        statusLore.add(legacySerialize("<yellow>Click to toggle"));
        statusMeta.setLore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(29, status);

        // Clear filters button (slot 31)
        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.setDisplayName(legacySerialize("<red>Clear All Filters"));
        List<String> clearLore = new ArrayList<>();
        clearLore.add("");
        clearLore.add(legacySerialize("<yellow>Click to remove all filter items"));
        clearMeta.setLore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(31, clearButton);

        // Info panel (slot 35)
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(legacySerialize("<aqua>Exporter Information"));
        List<String> infoLore = new ArrayList<>();
        infoLore.add(legacySerialize("<gray>Network: " + networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(legacySerialize("<gray>Filters: " + currentFilterItems.size() + "/18"));
        infoLore.add("");
        infoLore.add(legacySerialize("<yellow>Shift+Click items from inventory to add filters"));
        infoLore.add(legacySerialize("<yellow>Drag & drop items into filter area"));
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(35, info);
    }

    private Container getTargetContainer() {
        try {
            // Check adjacent blocks for containers
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        Block adjacent = exporterLocation.getBlock().getRelative(dx, dy, dz);
                        if (adjacent.getState() instanceof Container container) {
                            return container;
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking for target container: " + e.getMessage());
        }
        return null;
    }

    public void open(Player player) {
        player.openInventory(inventory);
        // Register this instance as a listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        // Handle status button click (slot 29)
        if (slot == 29) {
            event.setCancelled(true);
            handleToggleClick(player);
            return;
        }

        // Handle clear filters button click (slot 31)
        if (slot == 31) {
            event.setCancelled(true);
            handleClearFilters(player);
            return;
        }

        // Handle info book click (slot 35) - CANCEL BUT DO NOTHING
        if (slot == 35) {
            event.setCancelled(true);
            return;
        }

        // Handle shift-clicks from player inventory to add filters
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) &&
                slot >= inventory.getSize()) {

            ItemStack shiftClickedItem = event.getCurrentItem();
            if (shiftClickedItem != null && !shiftClickedItem.getType().isAir()) {
                event.setCancelled(true);
                handleAddItemToFilter(player, shiftClickedItem);
            }
            return;
        }

        // Handle clicks in filter area (slots 0-17) - ANY action should try to add to filter
        if (slot >= 0 && slot < 18) {
            handleFilterAreaClick(event, player, slot);
            return;
        }

        // Handle clicks on glass panes and other control elements - cancel them
        if (slot >= 18 && slot < inventory.getSize()) {
            event.setCancelled(true);
            return;
        }
    }

    private void handleFilterAreaClick(InventoryClickEvent event, Player player, int slot) {
        event.setCancelled(true);

        ItemStack cursorItem = event.getCursor();
        ItemStack slotItem = event.getCurrentItem();

        // PRIORITY 1: If player has items on cursor, try to add them to filter
        if (cursorItem != null && !cursorItem.getType().isAir()) {
            String itemHash = plugin.getItemManager().generateItemHash(cursorItem);

            // Check if already in filter
            boolean alreadyInFilter = false;
            for (ItemStack existingItem : currentFilterItems) {
                String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                if (itemHash.equals(existingHash)) {
                    alreadyInFilter = true;
                    break;
                }
            }

            if (alreadyInFilter) {
                player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
            } else if (currentFilterItems.size() >= 18) {
                player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
            } else {
                // Add to filter - don't consume the cursor item
                currentFilterItems.add(cursorItem.clone());
                saveFilters();
                setupGUI();
                player.sendMessage(Component.text("Added " + cursorItem.getType() + " to filter", NamedTextColor.GREEN));
            }
            return;
        }

        // PRIORITY 2: If clicking on existing filter item, remove it
        ItemStack filterItem = slotToFilterItem.get(slot);
        if (filterItem != null) {
            currentFilterItems.remove(filterItem);
            saveFilters();
            setupGUI();
            player.sendMessage(Component.text("Removed item from filter", NamedTextColor.YELLOW));
            return;
        }

        // PRIORITY 3: If empty slot, do nothing (already cancelled above)
    }

    private void handleAddItemToFilter(Player player, ItemStack itemToAdd) {
        // Check if already in filter (compare by hash since ItemStack.equals might not work reliably)
        String newItemHash = plugin.getItemManager().generateItemHash(itemToAdd);
        for (ItemStack existingItem : currentFilterItems) {
            String existingHash = plugin.getItemManager().generateItemHash(existingItem);
            if (newItemHash.equals(existingHash)) {
                player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                return;
            }
        }

        // Check if filter is full
        if (currentFilterItems.size() >= 18) {
            player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
            return;
        }

        // Add to filter (don't actually take the item from player)
        currentFilterItems.add(itemToAdd.clone());
        saveFilters();
        setupGUI(); // Refresh GUI to show new filter item
        player.sendMessage(Component.text("Added " + itemToAdd.getType() + " to filter", NamedTextColor.GREEN));
    }

    private void handleToggleClick(Player player) {
        try {
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data == null) return;

            // Can't enable without filters
            if (currentFilterItems.isEmpty() && !data.enabled) {
                player.sendMessage(Component.text("Cannot enable exporter without filter items!", NamedTextColor.RED));
                return;
            }

            boolean newState = !data.enabled;
            plugin.getExporterManager().toggleExporter(exporterId, newState);
            setupGUI(); // Refresh GUI

            player.sendMessage(Component.text("Exporter " + (newState ? "enabled" : "disabled"),
                    newState ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        } catch (Exception e) {
            player.sendMessage(Component.text("Error toggling exporter: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleClearFilters(Player player) {
        if (currentFilterItems.isEmpty()) {
            player.sendMessage(Component.text("No filters to clear", NamedTextColor.YELLOW));
            return;
        }

        currentFilterItems.clear();
        saveFilters();
        setupGUI();
        player.sendMessage(Component.text("Cleared all filters", NamedTextColor.YELLOW));
    }

    private void saveFilters() {
        try {
            plugin.getExporterManager().updateExporterFilter(exporterId, currentFilterItems);
        } catch (Exception e) {
            plugin.getLogger().severe("Error saving filters: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if dragging into the filter area vs control area
        boolean draggedIntoFilterArea = false;
        boolean draggedIntoControlArea = false;

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < 18) {
                draggedIntoFilterArea = true;
            } else if (slot >= 18 && slot < inventory.getSize()) {
                draggedIntoControlArea = true;
            }
        }

        if (draggedIntoControlArea) {
            // Dragging into control area - always cancel
            event.setCancelled(true);
            return;
        }

        if (draggedIntoFilterArea) {
            // Dragging into filter area - try to add item to filter
            ItemStack draggedItem = event.getOldCursor();

            if (draggedItem != null && !draggedItem.getType().isAir()) {
                // Cancel the default drag behavior
                event.setCancelled(true);

                // Check if already in filter
                String itemHash = plugin.getItemManager().generateItemHash(draggedItem);
                boolean alreadyInFilter = false;
                for (ItemStack existingItem : currentFilterItems) {
                    String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                    if (itemHash.equals(existingHash)) {
                        alreadyInFilter = true;
                        break;
                    }
                }

                if (alreadyInFilter) {
                    player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                } else if (currentFilterItems.size() >= 18) {
                    player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
                } else {
                    // Add to filter - keep original item on cursor (don't consume it)
                    currentFilterItems.add(draggedItem.clone());
                    saveFilters();
                    setupGUI();
                    player.sendMessage(Component.text("Added " + draggedItem.getType() + " to filter", NamedTextColor.GREEN));

                    // Keep the original item on cursor
                    event.setCursor(draggedItem);
                }
            } else {
                // No item being dragged or air - just cancel
                event.setCancelled(true);
            }
            return;
        }

        // If we get here, the drag is only in player inventory - allow it
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

    private String legacySerialize(String miniMessage) {
        Component component = this.miniMessage.deserialize(miniMessage);
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public Location getExporterLocation() {
        return exporterLocation;
    }

    public String getNetworkId() {
        return networkId;
    }
}