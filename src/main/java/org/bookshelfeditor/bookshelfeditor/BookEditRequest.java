package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

/**
 * A data class to hold information for editing a book from a web request.
 */
public class BookEditRequest {
    private String world;
    private int x;
    private int y;
    private int z;
    private int slot;
    private String title;
    private String author;
    private List<String> pages;

    // Getters for all fields
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getSlot() { return slot; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
}