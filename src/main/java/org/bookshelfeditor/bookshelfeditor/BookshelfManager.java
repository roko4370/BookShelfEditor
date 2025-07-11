package org.bookshelfeditor.bookshelfeditor;


import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BookshelfManager {

    private final JavaPlugin plugin;
    private final Logger logger;

    // Thread-safe set to replace HashSet
    private final Set<ChiseledBookshelfInfo> knownBookshelves =
            ConcurrentHashMap.newKeySet();

    private final File bookshelfFile;
    private final FileConfiguration bookshelfData;

    // Async save handling
    private final ScheduledExecutorService saveExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BookshelfSaveThread");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean saveScheduled = false;

    public BookshelfManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.bookshelfFile = new File(plugin.getDataFolder(), "bookshelves.yml");
        if (!bookshelfFile.exists()) {
            try {
                bookshelfFile.getParentFile().mkdirs();
                bookshelfFile.createNewFile();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not create bookshelves.yml!", e);
            }
        }
        this.bookshelfData = YamlConfiguration.loadConfiguration(bookshelfFile);
    }

    public void initialScan() {
        knownBookshelves.clear();
        for (World world : Bukkit.getWorlds()) {
            logger.info("Scanning world: " + world.getName());
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState blockState : chunk.getTileEntities()) {
                    if (blockState instanceof ChiseledBookshelf) {
                        addBookshelf(blockState.getBlock());
                    }
                }
            }
        }
        // Save once after initial scan (synchronously for initial setup)
        saveBookshelves();
    }

    public void loadBookshelves() {
        knownBookshelves.clear();
        List<String> locations = bookshelfData.getStringList("bookshelves");
        for (String locString : locations) {
            String[] parts = locString.split(";");
            if (parts.length == 4) {
                knownBookshelves.add(new ChiseledBookshelfInfo(
                        parts[0],
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                ));
            }
        }
    }

    public synchronized void saveBookshelves() {
        List<String> locations = knownBookshelves.stream()
                .map(info -> info.getWorld() + ";" + info.getX() + ";" + info.getY() + ";" + info.getZ())
                .collect(Collectors.toList());
        bookshelfData.set("bookshelves", locations);
        try {
            bookshelfData.save(bookshelfFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save bookshelves to file!", e);
        }
    }

    /**
     * Schedule an async save operation with 5-second debounce.
     * Multiple calls within the window are coalesced into a single save.
     */
    private void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;

        saveExecutor.schedule(() -> {
            try {
                saveBookshelves();
                logger.info("Async save completed for " + knownBookshelves.size() + " bookshelves");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to save bookshelves asynchronously", e);
            } finally {
                saveScheduled = false;
            }
        }, 5, TimeUnit.SECONDS);
    }

    public ChiseledBookshelfInfo addBookshelf(Block block) {
        Location loc = block.getLocation();
        if (loc.getWorld() == null) return null;

        ChiseledBookshelfInfo info = new ChiseledBookshelfInfo(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );

        if (knownBookshelves.add(info)) {
            // Schedule async save instead of immediate synchronous save
            scheduleSave();
            return info;
        }
        return null;
    }

    public void removeBookshelf(Block block) {
        Location loc = block.getLocation();
        if (loc.getWorld() == null) return;

        ChiseledBookshelfInfo info = new ChiseledBookshelfInfo(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );

        if (knownBookshelves.remove(info)) {
            // Schedule async save instead of immediate synchronous save
            scheduleSave();
        }
    }

    public Set<ChiseledBookshelfInfo> getAllBookshelves() {
        return Collections.unmodifiableSet(knownBookshelves);
    }

    /**
     * Edits a book in a chiseled bookshelf, loading the chunk if it is unloaded.
     * This version preserves the book type (Written vs. Book and Quill) and implements
     * a "virtual author" system for Books and Quills using display name and lore.
     *
     * @param location The location information of the bookshelf.
     * @param slot     The slot (0-5) to edit or place the book in.
     * @param title    The new title for the book.
     * @param author   The new author for the book.
     * @param pages    The new pages for the book.
     * @return A CompletableFuture that completes when the edit operation is finished.
     */
    public CompletableFuture<Void> editBookInBookshelf(ChiseledBookshelfInfo location, int slot, String title, String author, List<String> pages) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        World world = Bukkit.getWorld(location.getWorld());
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("World '" + location.getWorld() + "' not found."));
            return future;
        }

        if (slot < 0 || slot > 5) {
            future.completeExceptionally(new IllegalArgumentException("Slot must be between 0 and 5."));
            return future;
        }

        int chunkX = location.getX() >> 4;
        int chunkZ = location.getZ() >> 4;

        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    Block block = chunk.getBlock(location.getX() & 15, location.getY(), location.getZ() & 15);
                    if (block.getType() != Material.CHISELED_BOOKSHELF) {
                        future.completeExceptionally(new IllegalStateException("Block at location is not a chiseled bookshelf."));
                        return;
                    }

                    ChiseledBookshelf shelf = (ChiseledBookshelf) block.getState();
                    org.bukkit.inventory.ChiseledBookshelfInventory snapshotInventory = shelf.getSnapshotInventory();

                    // Check the existing item to determine what kind of book to create.
                    ItemStack existingItem = snapshotInventory.getItem(slot);
                    Material bookType = Material.WRITTEN_BOOK;
                    if (existingItem != null && existingItem.getType() == Material.WRITABLE_BOOK) {
                        bookType = Material.WRITABLE_BOOK;
                    }

                    ItemStack newBook = new ItemStack(bookType);
                    BookMeta meta = (BookMeta) newBook.getItemMeta();

                    if (meta != null) {
                        // --- START OF MODIFICATION ---
                        if (bookType == Material.WRITABLE_BOOK) {
                            // Virtual Author System for Book and Quill
                            meta.displayName(net.kyori.adventure.text.Component.text(title)); // Use Component for display name

                            // Create gray, non-italic lore using Paper's Component API
                            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                            lore.add(net.kyori.adventure.text.Component.text("by " + author, net.kyori.adventure.text.format.NamedTextColor.GRAY)
                                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                            lore.add(net.kyori.adventure.text.Component.text("Original", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));

                            meta.lore(lore);
                            meta.setPages(pages);
                        } else {
                            // Standard system for Written Books
                            meta.setTitle(title);
                            meta.setAuthor(author);
                            meta.setPages(pages);
                        }
                        // --- END OF MODIFICATION ---
                        newBook.setItemMeta(meta);
                    }

                    snapshotInventory.setItem(slot, newBook);
                    shelf.update(true, false);

                    logger.info(String.format("Successfully edited book (type: %s) in slot %d at bookshelf in world '%s' at %d, %d, %d",
                            bookType.name(), slot, location.getWorld(), location.getX(), location.getY(), location.getZ()));

                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastBookshelfUpdate(block);
                    }

                    future.complete(null);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to edit book in bookshelf on main thread", e);
                    future.completeExceptionally(e);
                }
            });
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Failed to load chunk for book edit", ex);
            future.completeExceptionally(ex);
            return null;
        });

        return future;
    }

    /**
     * Gracefully shutdown the save executor and ensure final save.
     * Called during plugin disable.
     */
    public void shutdown() {
        logger.info("Shutting down BookshelfManager...");

        // Stop accepting new tasks
        saveExecutor.shutdown();

        try {
            // Wait for pending saves to complete (max 6 seconds)
            if (!saveExecutor.awaitTermination(6, TimeUnit.SECONDS)) {
                logger.warning("Save executor did not terminate gracefully, forcing shutdown");
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warning("Interrupted while waiting for save executor shutdown");
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Ensure final save happens synchronously
        saveBookshelves();
        logger.info("BookshelfManager shutdown complete");
    }
}