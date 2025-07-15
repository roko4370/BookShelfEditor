/*  PlayerBookAddRequest.java  */
package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

/**
 * JSON body for POST /api/player/book/add
 * Always puts the book in the first empty slot of the chosen inventory.
 */
public class PlayerBookAddRequest {
    private String playerName;
    private String inventoryType;  // "INVENTORY" | "ENDERCHEST"
    private String title;          // optional
    private String author;         // optional
    private List<String> pages;    // optional

    public String getPlayerName()   { return playerName; }
    public String getInventoryType(){ return inventoryType; }
    public String getTitle()        { return title; }
    public String getAuthor()       { return author; }
    public List<String> getPages()  { return pages; }
}
