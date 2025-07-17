/*  BookAddRequest.java  */
package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

/**
 * JSON body for POST /api/bookshelf/book/add
 * `slot == -1` â†’ first empty slot.
 */
public class BookAddRequest {
    private String world;
    private int x, y, z;
    private int slot;              // 0-5  | -1 = first free
    private String title;          // optional
    private String author;         // optional
    private List<String> pages;    // optional

    public String getWorld()  { return world; }
    public int    getX()      { return x; }
    public int    getY()      { return y; }
    public int    getZ()      { return z; }
    public int    getSlot()   { return slot; }
    public String getTitle()  { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
}