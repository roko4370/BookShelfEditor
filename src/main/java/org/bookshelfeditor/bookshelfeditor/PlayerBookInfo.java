package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

public class PlayerBookInfo {
    private final int slot;
    private final String title;
    private final String author;
    private final List<String> pages;
    private final String playerName;
    private final String playerUuid;
    private final boolean isOnline;
    private final String inventoryType; // "INVENTORY", "ENDERCHEST"

    public PlayerBookInfo(int slot, String title, String author, List<String> pages,
                          String playerName, String playerUuid, boolean isOnline, String inventoryType) {
        this.slot = slot;
        this.title = title;
        this.author = author;
        this.pages = pages;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.isOnline = isOnline;
        this.inventoryType = inventoryType;
    }

    public int getSlot() { return slot; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
    public String getPlayerName() { return playerName; }
    public String getPlayerUuid() { return playerUuid; }
    public boolean isOnline() { return isOnline; }
    public String getInventoryType() { return inventoryType; }
}