package org.jamesphbennett.massstorageserver.storage;

import org.bukkit.inventory.ItemStack;

public class StoredItem {
    
    private final String itemHash;
    private final ItemStack itemStack;
    private final int quantity;
    
    public StoredItem(String itemHash, ItemStack itemStack, int quantity) {
        this.itemHash = itemHash;
        this.itemStack = itemStack.clone();
        this.quantity = quantity;
    }
    
    public String getItemHash() {
        return itemHash;
    }
    
    public ItemStack getItemStack() {
        return itemStack.clone();
    }
    
    public int getQuantity() {
        return quantity;
    }
    
    public ItemStack getDisplayStack() {
        ItemStack display = itemStack.clone();
        display.setAmount(Math.min(quantity, itemStack.getMaxStackSize()));
        return display;
    }
    
    @Override
    public String toString() {
        return String.format("StoredItem{hash='%s', item=%s, quantity=%d}", 
                itemHash, itemStack.getType(), quantity);
    }
}