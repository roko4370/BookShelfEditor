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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.entity.Player;
import org.bukkit.block.data.BlockData;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;




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
                    ItemStack existingItem = snapshotInventory.getItem(slot);
                    Material bookType = (existingItem != null && existingItem.getType() == Material.WRITABLE_BOOK) ? Material.WRITABLE_BOOK : Material.WRITTEN_BOOK;
                    ItemStack newBook = new ItemStack(bookType);
                    BookMeta meta = (BookMeta) newBook.getItemMeta();

                    // Handle title: Preserve empty if no input; allow literal "Untitled"
                    String finalTitle = (title != null && !title.trim().isEmpty()) ? title : "";
                    if (bookType == Material.WRITTEN_BOOK) {
                        meta.setTitle(finalTitle);
                    } else {
                        if (!finalTitle.isEmpty()) {
                            meta.displayName(Component.text(finalTitle));
                        } else {
                            meta.displayName(null);
                        }
                    }

                    // Handle author: Preserve empty if no input; allow literal "Unknown"
                    String finalAuthor = (author != null && !author.trim().isEmpty()) ? author : "";
                    if (bookType == Material.WRITTEN_BOOK) {
                        meta.setAuthor(finalAuthor);
                    } else {
                        if (!finalAuthor.isEmpty()) {
                            List<Component> lore = new ArrayList<>();
                            lore.add(Component.text("by " + finalAuthor, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                            lore.add(Component.text("Original", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                            meta.lore(lore);
                        } else {
                            meta.lore(null);
                        }
                    }

                    meta.setPages(pages);
                    newBook.setItemMeta(meta);
                    snapshotInventory.setItem(slot, newBook);
                    shelf.update(true, false);
                    logger.info(String.format("Successfully edited book in slot %d at bookshelf in world '%s' at %d, %d, %d",
                            slot, location.getWorld(), location.getX(), location.getY(), location.getZ()));
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastBookshelfUpdate(block);
                    }
                    future.complete(null);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to edit book in bookshelf", e);
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
     * Adds a book (writable by default) to a chiseled bookshelf.
     * Uses Paperâ€™s /item command so the client texture refreshes reliably [12].
     *
     * @param location  bookshelf co-ordinates
     * @param slot      0-5 or -1 for the first empty slot
     * @return future that completes when the book (and optional meta) is in place
     */
    public CompletableFuture<Void> addBookToBookshelf(
            ChiseledBookshelfInfo location,
            int slot,
            String title,
            String author,
            List<String> pages) {

        CompletableFuture<Void> future = new CompletableFuture<>();
        World world = Bukkit.getWorld(location.getWorld());
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("Unknown world"));
            return future;
        }

        int cx = location.getX() >> 4, cz = location.getZ() >> 4;
        world.getChunkAtAsync(cx, cz, true).thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Block block = chunk.getBlock(location.getX() & 15, location.getY(), location.getZ() & 15);
                if (block.getType() != Material.CHISELED_BOOKSHELF)
                    throw new IllegalStateException("Target is not a chiseled bookshelf.");

                ChiseledBookshelf shelf = (ChiseledBookshelf) block.getState();
                org.bukkit.inventory.ChiseledBookshelfInventory inv = shelf.getSnapshotInventory();

                int target = slot;
                if (target < 0) {                       // find first empty
                    target = -1;
                    for (int i = 0; i < 6; i++)
                        if (inv.getItem(i) == null) { target = i; break; }
                }
                if (target < 0 || target > 5)
                    throw new IllegalStateException("No free slot in bookshelf.");

                // 1. place an empty writable book via /item
                String cmd = String.format(
                        "item replace block %d %d %d container.%d with writable_book",
                        location.getX(), location.getY(), location.getZ(), target);
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd))
                    throw new IllegalStateException("Failed to execute /item command.");

                // 2. optional metadata â€“ reuse existing edit method for convenience
                if ((title != null && !title.isBlank())
                        || (author != null && !author.isBlank())
                        || (pages  != null && !pages.isEmpty())) {

                    editBookInBookshelf(location, target,
                            title, author,
                            pages != null ? pages : List.of());
                }

                // 3. broadcast
                if (plugin instanceof BookShelfEditor bse)
                    bse.broadcastBookshelfUpdate(block);

                future.complete(null);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        })).exceptionally(ex -> { future.completeExceptionally(ex); return null; });

        return future;
    }

    /**
     * Re-orders the six slots of a chiseled bookshelf in one go, using Paperâ€™s
     * `/item replace â€¦` command so that client-side slot textures refresh 100 %
     * reliably (the same trick used for add / delete).
     *
     * newOrder must contain the six *distinct* integers 0-5.
     * Each elementâ€™s INDEX = destination slot, ELEMENT = source slot.
     * Example â†’  [5,4,3,2,1,0]  â˜… reverses the bookshelf.
     *
     * @param location  world & xyz of the shelf
     * @param newOrder  6-element permutation of 0-5
     * @return          future that completes when every slot is in place
     */
    /**
     * Re-orders the six slots of a chiseled bookshelf.
     * â€“ Keeps texture updates reliable by using /item
     * â€“ Does NOT block the main thread (no .join()).
     */
    public CompletableFuture<Void> reorderBooksInBookshelf(
            ChiseledBookshelfInfo location,
            List<Integer> newOrder) {

        CompletableFuture<Void> overall = new CompletableFuture<>();

        /* â”€â”€â”€- sanity checks (unchanged) â”€â”€â”€ */
        if (newOrder == null || newOrder.size() != 6) {
            overall.completeExceptionally(
                    new IllegalArgumentException("newOrder must have exactly 6 entries"));
            return overall;
        }
        if (newOrder.stream().anyMatch(i -> i < 0 || i > 5)
                || newOrder.stream().distinct().count() != 6) {
            overall.completeExceptionally(
                    new IllegalArgumentException("newOrder must be a permutation of 0-5"));
            return overall;
        }

        World world = Bukkit.getWorld(location.getWorld());
        if (world == null) {
            overall.completeExceptionally(
                    new IllegalArgumentException("World '" + location.getWorld() + "' not found"));
            return overall;
        }

        /* â”€â”€â”€- chunk load â”€â”€â”€ */
        int cx = location.getX() >> 4, cz = location.getZ() >> 4;
        world.getChunkAtAsync(cx, cz, true).thenAccept(chunk ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        Block block = chunk.getBlock(location.getX() & 15,
                                location.getY(),
                                location.getZ() & 15);
                        if (block.getType() != Material.CHISELED_BOOKSHELF)
                            throw new IllegalStateException("Target block is not a chiseled bookshelf");

                        ChiseledBookshelf shelf = (ChiseledBookshelf) block.getState();
                        var inv = shelf.getSnapshotInventory();   // read-only is fine here

                        /* snapshot & desired */
                        ItemStack[] current  = new ItemStack[6];
                        ItemStack[] desired  = new ItemStack[6];
                        for (int i = 0; i < 6; i++) current[i] = inv.getItem(i);
                        for (int dst = 0; dst < 6; dst++)
                            desired[dst] = current[newOrder.get(dst)];

                        /* /item replace (textures) */
                        for (int slot = 0; slot < 6; slot++) {
                            ItemStack it = desired[slot];
                            String id = (it == null) ? "air"
                                    : (it.getType() == Material.WRITABLE_BOOK ? "writable_book"
                                    : "written_book");
                            String cmd = String.format(
                                    "item replace block %d %d %d container.%d with %s",
                                    location.getX(), location.getY(), location.getZ(), slot, id);
                            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd))
                                throw new IllegalStateException("Failed /item command for slot " + slot);
                        }

                        /* re-apply meta WITHOUT blocking the thread */
                        List<CompletableFuture<Void>> metaJobs = new ArrayList<>();
                        for (int slot = 0; slot < 6; slot++) {
                            ItemStack it = desired[slot];
                            if (it == null) continue;
                            BookMeta bm = (BookMeta) it.getItemMeta();

                            String title  = bm.hasDisplayName()
                                    ? PlainTextComponentSerializer.plainText()
                                    .serialize(bm.displayName()) : null;
                            String author = (it.getType() == Material.WRITABLE_BOOK)
                                    ? extractVirtualAuthor(bm)
                                    : (bm.hasAuthor() ? bm.getAuthor() : null);
                            List<String> pages = bm.hasPages() ? bm.getPages() : List.of();

                            if ((title != null && !title.isBlank())
                                    || (author != null && !author.isBlank())
                                    || !pages.isEmpty()) {
                                metaJobs.add(
                                        editBookInBookshelf(location, slot, title, author, pages)
                                );
                            }
                        }

                        /* when all metaJobs are done â€¦ */
                        CompletableFuture
                                .allOf(metaJobs.toArray(new CompletableFuture[0]))
                                .whenComplete((v, ex) -> {
                                    if (ex != null) {
                                        overall.completeExceptionally(ex);
                                        return;
                                    }
                                    if (plugin instanceof BookShelfEditor bse)
                                        bse.broadcastBookshelfUpdate(block);
                                    overall.complete(null);
                                });

                        /* quick exit â€“ main thread keeps running */

                    } catch (Exception e) {
                        overall.completeExceptionally(e);
                    }
                })
        ).exceptionally(ex -> { overall.completeExceptionally(ex); return null; });

        return overall;
    }


    /* Small helper: pulls virtual author from writable-book lore */
    private String extractVirtualAuthor(BookMeta meta) {
        if (!meta.hasLore() || meta.lore() == null || meta.lore().isEmpty()) return null;
        String line = PlainTextComponentSerializer.plainText().serialize(meta.lore().get(0));
        return line.startsWith("by ") ? line.substring(3) : null;
    }


    public CompletableFuture<Void> deleteBookInBookshelf(ChiseledBookshelfInfo location, int slot) {
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

                    // Step 1: Use /item command to replace the slot with air
                    String command = String.format("item replace block %d %d %d container.%d with air",
                            location.getX(), location.getY(), location.getZ(), slot);
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    if (!success) {
                        future.completeExceptionally(new IllegalStateException("Failed to execute /item command."));
                        return;
                    }

                    // Step 2: Reload block and shelf states to sync in-memory inventory
                    block.getState().update(true, true); // Force reload with physics
                    ChiseledBookshelf refreshedShelf = (ChiseledBookshelf) block.getState();
                    org.bukkit.inventory.ChiseledBookshelfInventory refreshedInventory = refreshedShelf.getSnapshotInventory();

                    // Verify and log the change
                    if (refreshedInventory.getItem(slot) != null) {
                        plugin.getLogger().warning("Slot " + slot + " not cleared after command at " + location + ". Forcing manual clear.");
                        refreshedInventory.setItem(slot, null);
                        refreshedShelf.update(true, true);
                    } else {
                        plugin.getLogger().info("Successfully cleared slot " + slot + " at " + location);
                    }

                    // Step 3: Force client sync for nearby players
                    Location shelfLoc = block.getLocation();
                    for (Player player : world.getNearbyPlayers(shelfLoc, 32)) {
                        player.sendBlockChange(shelfLoc, block.getBlockData());
                    }

                    // Step 4: Fallback dummy toggle for extra visual sync
                    int tempSlot = (slot + 1) % 6;
                    if (refreshedInventory.getItem(tempSlot) == null) {
                        ItemStack dummy = new ItemStack(Material.WRITABLE_BOOK);
                        BookMeta meta = (BookMeta) dummy.getItemMeta();
                        meta.setPages(List.of("Temp sync page"));
                        dummy.setItemMeta(meta);
                        refreshedInventory.setItem(tempSlot, dummy);
                        refreshedShelf.update(true, true);
                        refreshedInventory.setItem(tempSlot, null);
                        refreshedShelf.update(true, true);
                    }

                    // Step 5: Broadcast final update if applicable
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastBookshelfUpdate(block);
                    }

                    // Short delay to ensure sync before completing
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> future.complete(null), 5L);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }).exceptionally(ex -> {
            future.completeExceptionally(ex);
            return null;
        });
        return future;
    }


    public CompletableFuture<Void> lockBookInBookshelf(ChiseledBookshelfInfo location, int slot) {
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
                    ItemStack existingItem = snapshotInventory.getItem(slot);
                    if (existingItem == null || existingItem.getType() != Material.WRITABLE_BOOK) {
                        future.completeExceptionally(new IllegalStateException("No writable book in slot " + slot + " to lock."));
                        return;
                    }
                    BookMeta existingMeta = (BookMeta) existingItem.getItemMeta();
                    String title = existingMeta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(existingMeta.displayName()) : "";
                    String author = "";
                    if (existingMeta.hasLore() && existingMeta.lore() != null && !existingMeta.lore().isEmpty()) {
                        String loreText = PlainTextComponentSerializer.plainText().serialize(existingMeta.lore().get(0));
                        if (loreText.startsWith("by ")) {
                            author = loreText.substring(3);
                        }
                    }
                    List<String> pages = existingMeta.hasPages() ? existingMeta.getPages() : new ArrayList<>();
                    ItemStack newBook = new ItemStack(Material.WRITTEN_BOOK);
                    BookMeta newMeta = (BookMeta) newBook.getItemMeta();
                    newMeta.setTitle(title); // Preserve empty as empty
                    newMeta.setAuthor(author); // Preserve empty as empty
                    newMeta.setPages(pages);
                    newMeta.setGeneration(BookMeta.Generation.ORIGINAL);
                    newBook.setItemMeta(newMeta);
                    snapshotInventory.setItem(slot, newBook);
                    shelf.update(true, false);
                    logger.info(String.format("Successfully locked book in slot %d at bookshelf in world '%s' at %d, %d, %d",
                            slot, location.getWorld(), location.getX(), location.getY(), location.getZ()));
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastBookshelfUpdate(block);
                    }
                    future.complete(null);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to lock book in bookshelf", e);
                    future.completeExceptionally(e);
                }
            });
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Failed to load chunk for book lock", ex);
            future.completeExceptionally(ex);
            return null;
        });
        return future;
    }




    public CompletableFuture<Void> unlockBookInBookshelf(ChiseledBookshelfInfo location, int slot) {
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
                    ItemStack existingItem = snapshotInventory.getItem(slot);
                    if (existingItem == null || existingItem.getType() != Material.WRITTEN_BOOK) {
                        future.completeExceptionally(new IllegalStateException("No written book in slot " + slot + " to unlock."));
                        return;
                    }
                    BookMeta existingMeta = (BookMeta) existingItem.getItemMeta();
                    String title = existingMeta.hasTitle() ? existingMeta.getTitle() : "";
                    String author = existingMeta.hasAuthor() ? existingMeta.getAuthor() : "";
                    List<String> pages = existingMeta.hasPages() ? existingMeta.getPages() : new ArrayList<>();
                    ItemStack newBook = new ItemStack(Material.WRITABLE_BOOK);
                    BookMeta newMeta = (BookMeta) newBook.getItemMeta();
                    if (!title.isEmpty()) {
                        newMeta.displayName(Component.text(title));
                    }
                    if (!author.isEmpty()) {
                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("by " + author, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                        lore.add(Component.text("Original", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                        newMeta.lore(lore);
                    }
                    newMeta.setPages(pages);
                    newBook.setItemMeta(newMeta);
                    snapshotInventory.setItem(slot, newBook);
                    shelf.update(true, false);
                    logger.info(String.format("Successfully unlocked book in slot %d at bookshelf in world '%s' at %d, %d, %d",
                            slot, location.getWorld(), location.getX(), location.getY(), location.getZ()));
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastBookshelfUpdate(block);
                    }
                    future.complete(null);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to unlock book in bookshelf", e);
                    future.completeExceptionally(e);
                }
            });
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Failed to load chunk for book unlock", ex);
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