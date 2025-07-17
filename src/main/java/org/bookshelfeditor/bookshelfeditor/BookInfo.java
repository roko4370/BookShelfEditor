package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

public class BookInfo {
    private final int slot;
    private final String title;
    private final String author;
    private final List<String> pages;
    private final String type; // NEW: "WRITABLE_BOOK" or "WRITTEN_BOOK"

    public BookInfo(int slot, String title, String author, List<String> pages, String type) {
        this.slot = slot;
        this.title = title;
        this.author = author;
        this.pages = pages;
        this.type = type;
    }

    public int getSlot() { return slot; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
    public String getType() { return type; } // NEW: Getter for type
}