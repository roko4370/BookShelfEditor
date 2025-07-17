package org.bookshelfeditor.bookshelfeditor;

import java.util.List;

/** JSON body for POST /api/bookshelf/reorder */
public class BookshelfReorderRequest {

    private String world;
    private int x, y, z;
    private List<Integer> newOrder;   // exactly six ints, each 0-5

    public String       getWorld()   { return world;   }
    public int          getX()       { return x;       }
    public int          getY()       { return y;       }
    public int          getZ()       { return z;       }
    public List<Integer> getNewOrder(){ return newOrder; }
}