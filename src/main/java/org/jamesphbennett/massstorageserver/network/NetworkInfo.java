package org.jamesphbennett.massstorageserver.network;

import org.bukkit.Location;

import java.util.Set;

public class NetworkInfo {
    
    private final String networkId;
    private final Location storageServer;
    private final Set<Location> driveBays;
    private final Set<Location> terminals;
    private final Set<Location> allBlocks;
    
    public NetworkInfo(String networkId, Location storageServer, Set<Location> driveBays, 
                      Set<Location> terminals, Set<Location> allBlocks) {
        this.networkId = networkId;
        this.storageServer = storageServer;
        this.driveBays = driveBays;
        this.terminals = terminals;
        this.allBlocks = allBlocks;
    }
    
    public String getNetworkId() {
        return networkId;
    }
    
    public Location getStorageServer() {
        return storageServer;
    }
    
    public Set<Location> getDriveBays() {
        return driveBays;
    }
    
    public Set<Location> getTerminals() {
        return terminals;
    }
    
    public Set<Location> getAllBlocks() {
        return allBlocks;
    }
    
    public boolean isValid() {
        return storageServer != null && !driveBays.isEmpty() && !terminals.isEmpty();
    }
    
    @Override
    public String toString() {
        return String.format("NetworkInfo{id='%s', server=%s, driveBays=%d, terminals=%d}", 
                networkId, storageServer, driveBays.size(), terminals.size());
    }
}