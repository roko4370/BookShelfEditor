package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

/**
 * A data class to hold information for editing a book in a player's inventory from a web request.
 */
public class PlayerBookEditRequest {
    private String playerName;
    private int slot;
    private String title;
    private String author;
    private List<String> pages;
    private String inventoryType; // "INVENTORY" or "ENDERCHEST"

    // Getters for all fields
    public String getPlayerName() { return playerName; }
    public int getSlot() { return slot; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
    public String getInventoryType() { return inventoryType; }
}
