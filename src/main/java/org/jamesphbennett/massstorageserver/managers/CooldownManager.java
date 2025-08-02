package org.jamesphbennett.massstorageserver.managers;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
    
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final long cooldownDuration;

    public CooldownManager(long cooldownDurationMs) {
        this.cooldownDuration = cooldownDurationMs;
    }
    
    /**
     * Check if a player can perform an operation on a network
     */
    public boolean canOperate(UUID playerUUID, String networkId) {
        String key = generateKey(playerUUID, networkId);
        Long lastOperation = cooldowns.get(key);
        
        if (lastOperation == null) {
            return true;
        }
        
        return System.currentTimeMillis() - lastOperation >= cooldownDuration;
    }
    
    /**
     * Record that a player performed an operation on a network
     */
    public void recordOperation(UUID playerUUID, String networkId) {
        String key = generateKey(playerUUID, networkId);
        cooldowns.put(key, System.currentTimeMillis());
    }
    
    /**
     * Get remaining cooldown time in milliseconds
     */
    public long getRemainingCooldown(UUID playerUUID, String networkId) {
        String key = generateKey(playerUUID, networkId);
        Long lastOperation = cooldowns.get(key);
        
        if (lastOperation == null) {
            return 0;
        }
        
        long elapsed = System.currentTimeMillis() - lastOperation;
        return Math.max(0, cooldownDuration - elapsed);
    }

    /**
     * Clean up expired cooldowns (should be called periodically)
     */
    public void cleanupExpiredCooldowns() {
        long currentTime = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> currentTime - entry.getValue() >= cooldownDuration);
    }
    
    private String generateKey(UUID playerUUID, String networkId) {
        return playerUUID.toString() + ":" + networkId;
    }
}