package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

public class BookInfo {
    private final int slot;
    private final String title;
    private final String author;
    private final List<String> pages;

    public BookInfo(int slot, String title, String author, List<String> pages) {
        this.slot = slot;
        this.title = title;
        this.author = author;
        this.pages = pages;
    }

    public int getSlot() { return slot; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public List<String> getPages() { return pages; }
}