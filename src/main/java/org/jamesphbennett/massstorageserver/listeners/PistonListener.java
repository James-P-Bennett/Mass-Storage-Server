package org.jamesphbennett.massstorageserver.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

public class PistonListener implements Listener {

    private final MassStorageServer plugin;

    public PistonListener(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Prevent pistons from extending if they would push any custom MSS blocks or cables
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.isCancelled()) return;

        // Check all blocks that would be moved by this piston
        List<Block> blocksToMove = event.getBlocks();

        for (Block block : blocksToMove) {
            if (isCustomNetworkBlockOrCable(block)) {
                // Found a custom MSS block or cable in the path - cancel the entire piston event
                event.setCancelled(true);
                plugin.getLogger().info("Cancelled piston extend at " + event.getBlock().getLocation() +
                        " - would move custom MSS block/cable at " + block.getLocation());

                // Notify nearby players
                notifyNearbyPlayers(event.getBlock().getLocation(),
                        Component.text("Pistons cannot move Mass Storage blocks or cables!", NamedTextColor.RED));
                return;
            }
        }

        // Also check if the piston would push blocks into the space where custom blocks exist
        // This handles cases where pistons push other blocks into our custom blocks
        Block pistonBlock = event.getBlock();

        // Safe type checking to prevent ClassCastException
        if (!(pistonBlock.getBlockData() instanceof org.bukkit.block.data.type.Piston pistonData)) {
            plugin.getLogger().warning("Piston block data is not of type Piston: " + pistonBlock.getBlockData().getClass().getSimpleName());
            return;
        }

        org.bukkit.block.BlockFace direction = pistonData.getFacing();

        // Check each position where blocks would end up
        for (Block sourceBlock : blocksToMove) {
            // Calculate where this block would end up after being pushed
            Location targetLocation = sourceBlock.getLocation().clone().add(
                    direction.getModX(),
                    direction.getModY(),
                    direction.getModZ()
            );

            if (isCustomNetworkBlockOrCable(targetLocation.getBlock())) {
                // A block would be pushed into a custom MSS block - cancel
                event.setCancelled(true);
                plugin.getLogger().info("Cancelled piston extend at " + event.getBlock().getLocation() +
                        " - would push block into custom MSS block/cable at " + targetLocation);

                // Notify nearby players
                notifyNearbyPlayers(event.getBlock().getLocation(),
                        Component.text("Pistons cannot push blocks into Mass Storage blocks or cables!", NamedTextColor.RED));
                return;
            }
        }

        // Also check the front of the piston head (where a single block would be pushed to)
        if (!blocksToMove.isEmpty()) {
            Block frontBlock = blocksToMove.getLast();
            Location frontTarget = frontBlock.getLocation().clone().add(
                    direction.getModX(),
                    direction.getModY(),
                    direction.getModZ()
            );

            if (isCustomNetworkBlockOrCable(frontTarget.getBlock())) {
                event.setCancelled(true);
                plugin.getLogger().info("Cancelled piston extend at " + event.getBlock().getLocation() +
                        " - would push into custom MSS block/cable at " + frontTarget);

                // Notify nearby players
                notifyNearbyPlayers(event.getBlock().getLocation(),
                        Component.text("Pistons cannot push into Mass Storage blocks or cables!", NamedTextColor.RED));
            }
        }
    }

    /**
     * Prevent pistons from retracting if they would pull any custom MSS blocks or cables
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.isCancelled()) return;

        // Check all blocks that would be moved by this piston retraction
        List<Block> blocksToMove = event.getBlocks();

        for (Block block : blocksToMove) {
            if (isCustomNetworkBlockOrCable(block)) {
                // Found a custom MSS block or cable in the path - cancel the entire piston event
                event.setCancelled(true);
                plugin.getLogger().info("Cancelled piston retract at " + event.getBlock().getLocation() +
                        " - would move custom MSS block/cable at " + block.getLocation());

                // Notify nearby players
                notifyNearbyPlayers(event.getBlock().getLocation(),
                        Component.text("Pistons cannot pull Mass Storage blocks or cables!", NamedTextColor.RED));
                return;
            }
        }

        // For sticky pistons, also check if they would pull blocks into custom block spaces
        Block pistonBlock = event.getBlock();

        // Safe type checking to prevent ClassCastException
        if (!(pistonBlock.getBlockData() instanceof org.bukkit.block.data.type.Piston pistonData)) {
            plugin.getLogger().warning("Piston block data is not of type Piston: " + pistonBlock.getBlockData().getClass().getSimpleName());
            return;
        }

        org.bukkit.block.BlockFace direction = pistonData.getFacing();

        // Check each position where blocks would end up after retraction
        for (Block sourceBlock : blocksToMove) {
            // Calculate where this block would end up after being pulled
            Location targetLocation = sourceBlock.getLocation().clone().subtract(
                    direction.getModX(),
                    direction.getModY(),
                    direction.getModZ()
            );

            if (isCustomNetworkBlockOrCable(targetLocation.getBlock())) {
                // A block would be pulled into a custom MSS block - cancel
                event.setCancelled(true);
                plugin.getLogger().info("Cancelled piston retract at " + event.getBlock().getLocation() +
                        " - would pull block into custom MSS block/cable at " + targetLocation);

                // Notify nearby players
                notifyNearbyPlayers(event.getBlock().getLocation(),
                        Component.text("Pistons cannot pull blocks into Mass Storage blocks or cables!", NamedTextColor.RED));
                return;
            }
        }
    }

    /**
     * Check if a block is a custom network block or cable
     */
    private boolean isCustomNetworkBlockOrCable(Block block) {
        if (block == null || block.getType().isAir()) {
            return false;
        }

        // Check for custom network blocks (storage server, drive bay, terminal)
        if (isCustomNetworkBlock(block)) {
            return true;
        }

        // Check for custom network cables
        return plugin.getCableManager().isCustomNetworkCable(block);
    }

    /**
     * Check if a block is a custom network block (MSS block)
     */
    private boolean isCustomNetworkBlock(Block block) {
        if (block == null || block.getType().isAir()) {
            return false;
        }

        // First check material types that could be MSS blocks
        Material type = block.getType();
        if (type != Material.CHISELED_TUFF &&
                type != Material.CHISELED_TUFF_BRICKS &&
                type != Material.CRAFTER) {
            return false;
        }

        // Then check if this specific location is marked as an MSS block in our database
        return isMarkedAsMSSBlock(block.getLocation());
    }

    /**
     * Check if a location is marked as an MSS block in the database
     */
    private boolean isMarkedAsMSSBlock(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking MSS block marker for piston event: " + e.getMessage());
            return false;
        }
    }

    /**
     * Notify players within a reasonable range about the piston prevention
     */
    private void notifyNearbyPlayers(Location location, Component message) {
        if (location.getWorld() == null) return;

        // Notify players within 16 blocks
        location.getWorld().getNearbyEntities(location, 16, 16, 16).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .forEach(player -> player.sendMessage(message));
    }
}