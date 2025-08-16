package org.jamesphbennett.massstorageserver.storage;

import org.bukkit.inventory.ItemStack;

public record StoredItem(String itemHash, ItemStack itemStack, int quantity) {

    public StoredItem(String itemHash, ItemStack itemStack, int quantity) {
        this.itemHash = itemHash;
        this.itemStack = itemStack.clone();
        this.quantity = quantity;
    }

    @Override
    public ItemStack itemStack() {
        return itemStack.clone();
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