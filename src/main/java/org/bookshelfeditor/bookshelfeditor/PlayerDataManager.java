package org.bookshelfeditor.bookshelfeditor;

import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBTCompoundList;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import de.tr7zw.nbtapi.iface.ReadWriteNBTList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import com.google.gson.Gson;
import java.util.Map;

public class PlayerDataManager {
    private final JavaPlugin plugin;
    private final Gson gson = new Gson(); // ADD THIS LINE

    public PlayerDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public List<PlayerBookInfo> getPlayerBooks(String playerName, String inventoryType) {
        List<PlayerBookInfo> books = new ArrayList<>();

        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            books.addAll(getBooksFromOnlinePlayer(onlinePlayer, inventoryType));
        } else {
            books.addAll(getBooksFromOfflinePlayer(playerName, inventoryType));
        }

        return books;
    }

    public List<PlayerBookInfo> getAllPlayersBooks() {
        List<PlayerBookInfo> allBooks = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            allBooks.addAll(getBooksFromOnlinePlayer(player, "INVENTORY"));
            allBooks.addAll(getBooksFromOnlinePlayer(player, "ENDERCHEST"));
        }

        allBooks.addAll(getBooksFromAllOfflinePlayers());

        return allBooks;
    }

    private List<PlayerBookInfo> getBooksFromOnlinePlayer(Player player, String inventoryType) {
        List<PlayerBookInfo> books = new ArrayList<>();
        ItemStack[] contents = "ENDERCHEST".equals(inventoryType)
                ? player.getEnderChest().getContents()
                : player.getInventory().getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK)) {
                ItemMeta rawMeta = item.getItemMeta();
                if (rawMeta instanceof BookMeta meta) {
                    String bookTitle = "";
                    String bookAuthor = "";
                    List<String> bookPages = meta.hasPages() ? meta.getPages() : List.of("(This book is empty)");
                    if (item.getType() == Material.WRITABLE_BOOK) {
                        if (meta.hasDisplayName()) {
                            bookTitle = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        }
                        if (meta.hasLore() && meta.lore() != null && !meta.lore().isEmpty()) {
                            Component loreLine = meta.lore().get(0);
                            String loreText = PlainTextComponentSerializer.plainText().serialize(loreLine);
                            if (loreText.startsWith("by ")) {
                                bookAuthor = loreText.substring(3);
                            }
                        }
                    } else {
                        boolean isPlaceholder = meta.hasCustomModelData() && meta.getCustomModelData() == 1;
                        bookTitle = meta.hasTitle() ? (isPlaceholder && meta.getTitle().equalsIgnoreCase("Untitled") ? "" : meta.getTitle()) : "";
                        bookAuthor = meta.hasAuthor() ? (isPlaceholder && meta.getAuthor().equalsIgnoreCase("Unknown") ? "" : meta.getAuthor()) : "";
                    }
                    String bookType = item.getType().name();
                    books.add(new PlayerBookInfo(i, bookTitle, bookAuthor, bookPages, player.getName(), player.getUniqueId().toString(), true, inventoryType, bookType));


                }
            }
        }


        return books;
    }

    private List<PlayerBookInfo> getBooksFromOfflinePlayer(String playerName, String inventoryType) {
        List<PlayerBookInfo> books = new ArrayList<>();

        try {
            UUID playerUuid = getPlayerUuidByName(playerName);
            if (playerUuid == null) return books;

            File playerDataFile = getPlayerDataFile(playerUuid);
            if (!playerDataFile.exists()) return books;

            ReadWriteNBT playerData = NBT.readFile(playerDataFile);

            ReadWriteNBTCompoundList inventory = "ENDERCHEST".equals(inventoryType)
                    ? playerData.getCompoundList("EnderItems")
                    : playerData.getCompoundList("Inventory");

            for (ReadWriteNBT item : inventory) {
                String itemId = item.getString("id");
                if (!itemId.equals("minecraft:written_book") && !itemId.equals("minecraft:writable_book")) {
                    continue;
                }

                int slot = item.getByte("Slot");
                String bookTitle = "";
                String bookAuthor = "";
                List<String> bookPages = new ArrayList<>();
                String bookType = itemId.equals("minecraft:writable_book") ? "WRITABLE_BOOK" : "WRITTEN_BOOK";

                if (item.hasTag("components")) {
                    ReadWriteNBT components = item.getCompound("components");
                    if (bookType.equals("WRITABLE_BOOK")) {
                        // Writable books use custom_name and lore for virtual data
                        if (components.hasTag("minecraft:custom_name")) {
                            bookTitle = parseTextComponent(components.getString("minecraft:custom_name"));
                        }
                        if (components.hasTag("minecraft:lore")) {
                            var loreList = components.getStringList("minecraft:lore");
                            if (loreList != null && !loreList.isEmpty()) {
                                String loreText = parseTextComponent(loreList.get(0));
                                if (loreText.startsWith("by ")) {
                                    bookAuthor = loreText.substring(3);
                                }
                            }
                        }
                        if (components.hasTag("minecraft:writable_book_content")) {
                            ReadWriteNBT bookContent = components.getCompound("minecraft:writable_book_content");
                            if (bookContent.hasTag("pages")) {
                                ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                                for (ReadWriteNBT page : pagesList) {
                                    if (page.hasTag("raw")) bookPages.add(page.getString("raw"));
                                }
                            }
                        }
                    } else { // WRITTEN_BOOK
                        if (components.hasTag("minecraft:written_book_content")) {
                            ReadWriteNBT bookContent = components.getCompound("minecraft:written_book_content");
                            // Title is a compound with a raw key holding a plain string.
                            if (bookContent.hasTag("title") && bookContent.getCompound("title").hasTag("raw")) {
                                bookTitle = bookContent.getCompound("title").getString("raw");
                            }
                            // Author is a plain string.
                            if (bookContent.hasTag("author")) {
                                bookAuthor = bookContent.getString("author");
                            }
                            // Pages are compounds with a raw key holding a plain string.
                            if (bookContent.hasTag("pages")) {
                                ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                                for (ReadWriteNBT page : pagesList) {
                                    if (page.hasTag("raw")) bookPages.add(page.getString("raw"));
                                }
                            }
                        }
                        // NEW: Placeholder check for offline written books
                        boolean isPlaceholder = false;
                        if (components.hasTag("minecraft:custom_model_data")) {
                            int customModelData = components.getInteger("minecraft:custom_model_data");
                            isPlaceholder = (customModelData == 1);
                        }
                        if (isPlaceholder && bookTitle.equalsIgnoreCase("Untitled")) {
                            bookTitle = "";
                        }
                        if (isPlaceholder && bookAuthor.equalsIgnoreCase("Unknown")) {
                            bookAuthor = "";
                        }
                    }
                } else if (item.hasTag("tag")) { // Legacy format handling
                    ReadWriteNBT tag = item.getCompound("tag");
                    if (tag.hasTag("title")) bookTitle = tag.getString("title");
                    if (tag.hasTag("author")) bookAuthor = tag.getString("author");
                    if (tag.hasTag("pages")) {
                        for (String page : tag.getStringList("pages")) {
                            bookPages.add(page);
                        }
                    }
                }

                if (bookPages.isEmpty()) {
                    bookPages.add("(This book is empty)");
                }

                books.add(new PlayerBookInfo(
                        slot, bookTitle, bookAuthor, bookPages,
                        playerName, playerUuid.toString(),
                        false, inventoryType, bookType
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read player data for " + playerName, e);
        }

        return books;
    }




    private List<PlayerBookInfo> getBooksFromAllOfflinePlayers() {
        List<PlayerBookInfo> books = new ArrayList<>();
        File playerDataDir = getPlayerDataDirectory();
        if (!playerDataDir.isDirectory()) return books;

        File[] playerFiles = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
        if (playerFiles == null) return books;

        for (File playerFile : playerFiles) {
            try {
                UUID playerUuid = UUID.fromString(playerFile.getName().replace(".dat", ""));
                if (Bukkit.getPlayer(playerUuid) != null) continue;

                String playerName = getPlayerNameByUuid(playerUuid);
                if (playerName != null) {
                    books.addAll(getBooksFromOfflinePlayer(playerName, "INVENTORY"));
                    books.addAll(getBooksFromOfflinePlayer(playerName, "ENDERCHEST"));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process player file " + playerFile.getName(), e);
            }
        }

        return books;
    }

    private File getPlayerDataDirectory() {
        World mainWorld = Bukkit.getWorlds().get(0);
        return new File(mainWorld.getWorldFolder(), "playerdata");
    }

    private File getPlayerDataFile(UUID playerUuid) {
        return new File(getPlayerDataDirectory(), playerUuid + ".dat");
    }

    private UUID getPlayerUuidByName(String playerName) {
        Player online = Bukkit.getPlayer(playerName);
        if (online != null) return online.getUniqueId();
        var offline = Bukkit.getOfflinePlayer(playerName);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    private String getPlayerNameByUuid(UUID playerUuid) {
        Player online = Bukkit.getPlayer(playerUuid);
        if (online != null) return online.getName();
        var offline = Bukkit.getOfflinePlayer(playerUuid);
        return offline.hasPlayedBefore() ? offline.getName() : null;
    }

    /**
     * Edits a book in a player's inventory (online or offline), preserving type and virtual author.
     * @param playerName The name of the player.
     * @param slot The slot to edit.
     * @param title The new title.
     * @param author The new author.
     * @param pages The new pages.
     * @param inventoryType "INVENTORY" or "ENDERCHEST".
     * @return A CompletableFuture that completes when the edit is done.
     */
    public CompletableFuture<Void> editBookInPlayerInventory(String playerName,
                                                             int slot,
                                                             String title,
                                                             String author,
                                                             List<String> pages,
                                                             String inventoryType) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        /* ------------------------------------------------- *
         *  ONLINE PLAYERS â€“ use the Bukkit API (unchanged)  *
         * ------------------------------------------------- */
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {

            // --- NEW: run immediately when we are already on the main thread ---
            Runnable editTask = () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType)
                            ? online.getEnderChest()
                            : online.getInventory();

                    ItemStack item = inv.getItem(slot);
                    if (item == null ||
                            (item.getType() != Material.WRITTEN_BOOK && item.getType() != Material.WRITABLE_BOOK)) {
                        future.completeExceptionally(
                                new IllegalStateException("No book in slot " + slot));
                        return;
                    }

                    boolean isWritten = item.getType() == Material.WRITTEN_BOOK;
                    BookMeta meta = (BookMeta) item.getItemMeta();

                    /* -------- title -------- */
                    String cleanTitle = title != null ? title.trim() : "";
                    if (isWritten) {
                        if (!cleanTitle.isEmpty()) meta.setTitle(cleanTitle);  // signed book
                    } else {                                                  // book & quill
                        meta.displayName(cleanTitle.isEmpty()
                                ? null
                                : Component.text(cleanTitle));
                    }

                    /* -------- author -------- */
                    String cleanAuthor = author != null ? author.trim() : "";
                    if (isWritten) {
                        if (!cleanAuthor.isEmpty()) meta.setAuthor(cleanAuthor);
                    } else {
                        if (cleanAuthor.isEmpty()) {
                            meta.lore(null);
                        } else {
                            meta.lore(List.of(
                                    Component.text("by " + cleanAuthor, NamedTextColor.GRAY)
                                            .decoration(TextDecoration.ITALIC, false),
                                    Component.text("Original", NamedTextColor.GRAY)
                                            .decoration(TextDecoration.ITALIC, false)
                            ));
                        }
                    }

                    /* -------- pages -------- */
                    meta.setPages(pages);

                    /* -------- finalise -------- */
                    item.setItemMeta(meta);
                    inv.setItem(slot, item);
                    online.updateInventory();
                    if (plugin instanceof BookShelfEditor bse) {
                        bse.broadcastPlayerBookUpdate(playerName, online.getUniqueId().toString());
                    }
                    future.complete(null);
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }

            };  // ← end of editTask

            if (plugin.getServer().isPrimaryThread()) {
                editTask.run();                     // already on main thread
            } else {
                plugin.getServer().getScheduler()
                        .runTask(plugin, editTask);   // hop to main thread
            }
            return future;
        }


        /* ------------------------------------------------ *
         *  OFFLINE PLAYERS â€“ direct NBT file manipulation  *
         * ------------------------------------------------ */
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID uuid = getPlayerUuidByName(playerName);
                if (uuid == null) {
                    future.completeExceptionally(new IllegalArgumentException("Unknown player."));
                    return;
                }

                File dataFile = getPlayerDataFile(uuid);
                ReadWriteNBT playerData = NBT.readFile(dataFile);
                ReadWriteNBTCompoundList inv = "ENDERCHEST".equals(inventoryType)
                        ? playerData.getCompoundList("EnderItems")
                        : playerData.getCompoundList("Inventory");

                ReadWriteNBT target = null;
                for (ReadWriteNBT nbtItem : inv) {
                    if (nbtItem.getByte("Slot") == slot) {
                        target = nbtItem;
                        break;
                    }
                }
                if (target == null) {
                    future.completeExceptionally(
                            new IllegalStateException("No book in slot " + slot));
                    return;
                }

                boolean isWritten = target.getString("id").equals("minecraft:written_book");
                ReadWriteNBT components = target.getOrCreateCompound("components");

                /* -------- title -------- */
                String cleanTitle = title != null ? title.trim() : "";
                if (isWritten) {
                    ReadWriteNBT book = components.getOrCreateCompound("minecraft:written_book_content");
                    if (cleanTitle.isEmpty()) {
                        book.removeKey("title");
                    } else {
                        book.getOrCreateCompound("title").setString("raw", cleanTitle);
                    }
                } else {
                    if (cleanTitle.isEmpty()) {
                        components.removeKey("minecraft:custom_name");
                    } else {
                        components.setString("minecraft:custom_name",
                                gson.toJson(Map.of("text", cleanTitle)));
                    }
                }

                /* -------- author -------- */
                String cleanAuthor = author != null ? author.trim() : "";
                if (isWritten) {
                    ReadWriteNBT book = components.getOrCreateCompound("minecraft:written_book_content");
                    if (cleanAuthor.isEmpty()) {
                        book.removeKey("author");
                    } else {
                        book.setString("author", cleanAuthor);
                    }
                } else {
                    if (cleanAuthor.isEmpty()) {
                        components.removeKey("minecraft:lore");
                    } else {
                        ReadWriteNBTList<String> lore = components.getStringList("minecraft:lore");
                        lore.clear();
                        lore.add(gson.toJson(Map.of(
                                "text", "by " + author,
                                "color", "gray",
                                "italic", false
                        )));
                        lore.add(gson.toJson(Map.of(
                                "text", "Original",
                                "color", "gray",
                                "italic", false
                        )));
                    }
                }

                /* -------- pages -------- */
                ReadWriteNBT content = isWritten
                        ? components.getOrCreateCompound("minecraft:written_book_content")
                        : components.getOrCreateCompound("minecraft:writable_book_content");

                ReadWriteNBTCompoundList pageList = content.getCompoundList("pages");
                pageList.clear();
                for (String p : pages) {
                    pageList.addCompound().setString("raw", p);
                }

                /* -------- save back to disk -------- */
                NBT.writeFile(dataFile, playerData);
                future.complete(null);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }



    public CompletableFuture<Void> lockBookInPlayerInventory(String playerName, int slot, String inventoryType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            // Online player logic (already correct)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType) ? onlinePlayer.getEnderChest() : onlinePlayer.getInventory();
                    ItemStack existingItem = inv.getItem(slot);
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
                    newMeta.setTitle(title);
                    newMeta.setAuthor(author);
                    newMeta.setPages(pages);
                    newMeta.setGeneration(BookMeta.Generation.ORIGINAL);
                    newBook.setItemMeta(newMeta);
                    inv.setItem(slot, newBook);
                    onlinePlayer.updateInventory();
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastPlayerBookUpdate(playerName, onlinePlayer.getUniqueId().toString());
                    }
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            // Offline player logic
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    UUID playerUuid = getPlayerUuidByName(playerName);
                    if (playerUuid == null) {
                        future.completeExceptionally(new IllegalArgumentException("Player UUID not found."));
                        return;
                    }
                    File playerDataFile = getPlayerDataFile(playerUuid);
                    ReadWriteNBT playerData = NBT.readFile(playerDataFile);
                    ReadWriteNBTCompoundList inventory = "ENDERCHEST".equals(inventoryType) ? playerData.getCompoundList("EnderItems") : playerData.getCompoundList("Inventory");
                    ReadWriteNBT targetItem = null;
                    for (ReadWriteNBT item : inventory) {
                        if (item.getByte("Slot") == slot && item.getString("id").equals("minecraft:writable_book")) {
                            targetItem = item;
                            break;
                        }
                    }
                    if (targetItem == null) {
                        future.completeExceptionally(new IllegalStateException("No writable book in slot " + slot + " to lock."));
                        return;
                    }

                    String title = "";
                    String author = "";
                    List<String> pages = new ArrayList<>();
                    ReadWriteNBT components = targetItem.getOrCreateCompound("components");
                    if (components.hasTag("minecraft:custom_name")) title = parseTextComponent(components.getString("minecraft:custom_name"));
                    if (components.hasTag("minecraft:lore")) {
                        var loreList = components.getStringList("minecraft:lore");
                        if (loreList != null && !loreList.isEmpty()) {
                            String loreText = parseTextComponent(loreList.get(0));
                            if (loreText.startsWith("by ")) author = loreText.substring(3);
                        }
                    }
                    if (components.hasTag("minecraft:writable_book_content")) {
                        ReadWriteNBT bookContent = components.getCompound("minecraft:writable_book_content");
                        if (bookContent.hasTag("pages")) {
                            ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                            for (ReadWriteNBT page : pagesList) {
                                if (page.hasTag("raw")) pages.add(parseTextComponent(page.getString("raw")));
                            }
                        }
                    }

                    // --- convert writable â†’ written (offline) ---
                    targetItem.setString("id", "minecraft:written_book");

                    components.removeKey("minecraft:custom_name");
                    components.removeKey("minecraft:lore");
                    components.removeKey("minecraft:writable_book_content");

                    components.setInteger("minecraft:custom_model_data", 1);

                    ReadWriteNBT written = components.getOrCreateCompound("minecraft:written_book_content");

                    if (!title.isEmpty()) {
                        written.getOrCreateCompound("title").setString("raw", title);
                    } else {
                        written.getOrCreateCompound("title").setString("raw", "Untitled");
                    }

// author
                    written.setString("author", author.isEmpty() ? "Unknown" : author);
                    written.setInteger("generation", 0);

// pages
                    ReadWriteNBTCompoundList p = written.getCompoundList("pages");
                    p.clear();
                    for (String page : pages) {
                        p.addCompound().setString("raw", page);
                    }

                    NBT.writeFile(playerDataFile, playerData);   // File first!

                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }


    // -----------------------------------------------------------------------------
//  PlayerDataManager â€“ completely fixed unlockBookInPlayerInventory(..)
// -----------------------------------------------------------------------------
    public CompletableFuture<Void> unlockBookInPlayerInventory(String playerName,
                                                               int slot,
                                                               String inventoryType) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        /* ---------------- ONLINE ---------------- */
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType)
                            ? online.getEnderChest()
                            : online.getInventory();

                    ItemStack item = inv.getItem(slot);
                    if (item == null || item.getType() != Material.WRITTEN_BOOK) {
                        future.completeExceptionally(
                                new IllegalStateException("No written book in slot " + slot));
                        return;
                    }

                    BookMeta meta = (BookMeta) item.getItemMeta();
                    String t = meta.hasTitle() ? meta.getTitle() : "";
                    String a = meta.hasAuthor() ? meta.getAuthor() : "";
                    List<String> p = meta.hasPages() ? meta.getPages() : new ArrayList<>();

                    ItemStack quill = new ItemStack(Material.WRITABLE_BOOK);
                    BookMeta qMeta = (BookMeta) quill.getItemMeta();

                    if (!t.isEmpty()) qMeta.displayName(Component.text(t));
                    if (!a.isEmpty()) {
                        qMeta.lore(List.of(
                                Component.text("by " + a, NamedTextColor.GRAY)
                                        .decoration(TextDecoration.ITALIC, false),
                                Component.text("Original", NamedTextColor.GRAY)
                                        .decoration(TextDecoration.ITALIC, false)
                        ));
                    }
                    qMeta.setPages(p);
                    quill.setItemMeta(qMeta);

                    inv.setItem(slot, quill);
                    online.updateInventory();
                    if (plugin instanceof BookShelfEditor bse) {
                        bse.broadcastPlayerBookUpdate(playerName, online.getUniqueId().toString());
                    }
                    future.complete(null);
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
            return future;
        }

        /* ---------------- OFFLINE ---------------- */
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID uuid = getPlayerUuidByName(playerName);
                if (uuid == null) {
                    future.completeExceptionally(new IllegalArgumentException("Unknown player."));
                    return;
                }

                File dataFile = getPlayerDataFile(uuid);
                ReadWriteNBT playerData = NBT.readFile(dataFile);
                ReadWriteNBTCompoundList inv = "ENDERCHEST".equals(inventoryType)
                        ? playerData.getCompoundList("EnderItems")
                        : playerData.getCompoundList("Inventory");

                ReadWriteNBT book = null;
                for (ReadWriteNBT nbtItem : inv) {
                    if (nbtItem.getByte("Slot") == slot &&
                            nbtItem.getString("id").equals("minecraft:written_book")) {
                        book = nbtItem;
                        break;
                    }
                }
                if (book == null) {
                    future.completeExceptionally(
                            new IllegalStateException("No written book in slot " + slot + " to unlock."));
                    return;
                }

                /* -------- extract existing data -------- */
                ReadWriteNBT components = book.getOrCreateCompound("components");
                String title = "";
                String author = "";
                List<String> pages = new ArrayList<>();

                if (components.hasTag("minecraft:written_book_content")) {
                    ReadWriteNBT w = components.getCompound("minecraft:written_book_content");

                    if (w.hasTag("title") && w.getCompound("title").hasTag("raw"))
                        title = w.getCompound("title").getString("raw");
                    if (w.hasTag("author"))
                        author = w.getString("author");
                    if (w.hasTag("pages")) {
                        for (ReadWriteNBT pg : w.getCompoundList("pages")) {
                            if (pg.hasTag("raw")) pages.add(pg.getString("raw"));
                        }
                    }
                }

                /* -------- convert to writable -------- */
                book.setString("id", "minecraft:writable_book");
                components.removeKey("minecraft:written_book_content");

                if (title.isEmpty()) {
                    components.removeKey("minecraft:custom_name");
                } else {
                    components.setString("minecraft:custom_name",
                            gson.toJson(Map.of("text", title)));
                }

                if (author.isEmpty()) {
                    components.removeKey("minecraft:lore");
                } else {
                    ReadWriteNBTList<String> lore = components.getStringList("minecraft:lore");
                    lore.clear();
                    lore.add(gson.toJson(Map.of(
                            "text", "by " + author,
                            "color", "gray",
                            "italic", false
                    )));
                    lore.add(gson.toJson(Map.of(
                            "text", "Original",
                            "color", "gray",
                            "italic", false
                    )));
                }

                ReadWriteNBT writable = components.getOrCreateCompound("minecraft:writable_book_content");
                ReadWriteNBTCompoundList pageList = writable.getCompoundList("pages");
                pageList.clear();
                for (String pg : pages) pageList.addCompound().setString("raw", pg);

                /* Mark as placeholder so the read-side hides Untitled/Unknown */
                components.setInteger("minecraft:custom_model_data", 1);

                /* -------- save -------- */
                NBT.writeFile(dataFile, playerData);
                future.complete(null);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }


    /**
     * Adds a writable book to the first empty slot of the chosen inventory.
     * Online â†’ /item command; Offline â†’ NBT file.
     */
    public CompletableFuture<Void> addBookToPlayerInventory(
            String playerName,
            String inventoryType,
            String title,
            String author,
            List<String> pages) {

        CompletableFuture<Void> future = new CompletableFuture<>();
        Player online = Bukkit.getPlayerExact(playerName);

        /* ---------------- ONLINE ---------------- */
        if (online != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    // Which inventory are we working with?
                    org.bukkit.inventory.Inventory inv =
                            "ENDERCHEST".equals(inventoryType)
                                    ? online.getEnderChest()
                                    : online.getInventory();

                    // First free slot
                    int slot = inv.firstEmpty();
                    if (slot < 0) {                       // inventory full
                        future.completeExceptionally(
                                new IllegalStateException("Inventory full."));
                        return;
                    }

                    /* ---------- create the book ---------- */
                    boolean hasPages = pages != null && !pages.isEmpty();
                    Material type   = hasPages ? Material.WRITTEN_BOOK
                            : Material.WRITABLE_BOOK;
                    ItemStack book  = new ItemStack(type, 1);

                    BookMeta meta = (BookMeta) book.getItemMeta();
                    if (title  != null && !title.isBlank())   meta.setTitle(title);
                    if (author != null && !author.isBlank())  meta.setAuthor(author);
                    if (hasPages)                             meta.setPages(pages);
                    book.setItemMeta(meta);

                    /* ---------- give it to the player ---------- */
                    inv.setItem(slot, book);
                    online.updateInventory();   // force client sync (safe no-op on 1.17+)

                    /* ---------- optional broadcast ---------- */
                    if (plugin instanceof BookShelfEditor bse) {
                        bse.broadcastPlayerBookUpdate(
                                playerName, online.getUniqueId().toString());
                    }

                    future.complete(null);

                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
            return future;
        }

        /* ---------------- OFFLINE ---------------- */
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID uuid = getPlayerUuidByName(playerName);
                if (uuid == null) throw new IllegalArgumentException("Unknown player.");

                File dataFile = getPlayerDataFile(uuid);
                ReadWriteNBT playerData = NBT.readFile(dataFile);
                ReadWriteNBTCompoundList inv =
                        "ENDERCHEST".equals(inventoryType)
                                ? playerData.getCompoundList("EnderItems")
                                : playerData.getCompoundList("Inventory");

                // locate first empty slot index 0â€“40
                boolean[] used = new boolean[41];
                for (ReadWriteNBT tag : inv) used[tag.getByte("Slot")] = true;
                int slot = -1;
                for (int i = 0; i < used.length; i++)
                    if (!used[i]) { slot = i; break; }
                if (slot == -1)
                    throw new IllegalStateException("Inventory full.");

                // build brand-new NBT entry
                ReadWriteNBT tag = NBT.createNBTObject();
                tag.setString("id", "minecraft:writable_book");
                tag.setByte("Slot", (byte) slot);
                ReadWriteNBT components = tag.getOrCreateCompound("components");

                // store pages, title, author if supplied
                if (pages != null && !pages.isEmpty()) {
                    ReadWriteNBT writable = components.getOrCreateCompound("minecraft:writable_book_content");
                    ReadWriteNBTCompoundList pageList = writable.getCompoundList("pages");
                    for (String p : pages) pageList.addCompound().setString("raw", p);
                }
                if (title != null && !title.isBlank())
                    components.setString("minecraft:custom_name",
                            gson.toJson(Map.of("text", title)));
                if (author != null && !author.isBlank()) {
                    ReadWriteNBTList lore = components.getStringList("minecraft:lore");
                    lore.add(gson.toJson(Map.of("text", "by " + author,
                            "color", "gray", "italic", false)));
                    lore.add(gson.toJson(Map.of("text", "Original",
                            "color", "gray", "italic", false)));
                }

                ReadWriteNBT newEntry = inv.addCompound();  // creates empty compound, returns it
                newEntry.mergeCompound(tag);
                NBT.writeFile(dataFile, playerData);

                future.complete(null);
            } catch (Exception ex) { future.completeExceptionally(ex); }
        });
        return future;
    }



    public CompletableFuture<Void> deleteBookInPlayerInventory(String playerName, int slot, String inventoryType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            // Online player: Edit directly on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType) ? onlinePlayer.getEnderChest() : onlinePlayer.getInventory();
                    ItemStack existingItem = inv.getItem(slot);
                    if (existingItem == null || (existingItem.getType() != Material.WRITTEN_BOOK && existingItem.getType() != Material.WRITABLE_BOOK)) {
                        future.completeExceptionally(new IllegalStateException("No book in slot " + slot));
                        return;
                    }
                    inv.setItem(slot, null); // Remove the book
                    onlinePlayer.updateInventory();
                    if (plugin instanceof BookShelfEditor) {
                        ((BookShelfEditor) plugin).broadcastPlayerBookUpdate(playerName, onlinePlayer.getUniqueId().toString());
                    }
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            // Offline player: Use NBT async
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    UUID playerUuid = getPlayerUuidByName(playerName);
                    if (playerUuid == null) {
                        future.completeExceptionally(new IllegalArgumentException("Player UUID not found."));
                        return;
                    }
                    File playerDataFile = getPlayerDataFile(playerUuid);
                    if (!playerDataFile.exists()) {
                        future.completeExceptionally(new IllegalStateException("Player data file not found."));
                        return;
                    }
                    ReadWriteNBT playerData = NBT.readFile(playerDataFile);
                    ReadWriteNBTCompoundList inventory = "ENDERCHEST".equals(inventoryType) ? playerData.getCompoundList("EnderItems") : playerData.getCompoundList("Inventory");

                    // Find the index of the target item
                    int indexToRemove = -1;
                    for (int i = 0; i < inventory.size(); i++) {
                        ReadWriteNBT item = inventory.get(i);
                        if (item.getByte("Slot") == slot) {
                            String itemId = item.getString("id");
                            if (itemId.equals("minecraft:written_book") || itemId.equals("minecraft:writable_book")) {
                                indexToRemove = i;
                                break;
                            }
                        }
                    }

                    if (indexToRemove == -1) {
                        future.completeExceptionally(new IllegalStateException("No book in slot " + slot));
                        return;
                    }

                    // Remove the item by index
                    inventory.remove(indexToRemove);

                    NBT.writeFile(playerDataFile, playerData);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }

        return future;
    }


    /**
     * Safely parses a text component from a raw NBT string.
     * It handles three cases:
     * 1. A full JSON object like {"text":"..."}.
     * 2. A JSON-encoded string like "\"My Title\"".
     * 3. A plain, un-encoded string like "My Title".
     *
     * @param rawNbtString The string directly from the NBT tag.
     * @return The cleaned, visible text.
     */
    private String parseTextComponent(String rawNbtString) {
        if (rawNbtString == null || rawNbtString.isBlank()) {
            return "";
        }
        // Try to parse as a JSON object first (e.g., {"text":"value"})
        if (rawNbtString.startsWith("{") && rawNbtString.endsWith("}")) {
            try {
                Map<String, String> map = gson.fromJson(rawNbtString, Map.class);
                if (map != null && map.containsKey("text")) {
                    return map.get("text");
                }
                // Handle legacy raw/text structure
                if (map != null && map.containsKey("raw")) {
                    return map.get("raw");
                }
            } catch (Exception e) {
                // Fall through if it's not a valid map object
            }
        }
        // If not a JSON object, try to parse it as a JSON string literal.
        // This will correctly un-escape and remove surrounding quotes (e.g., "\"value\"" -> "value").
        try {
            String decoded = gson.fromJson(rawNbtString, String.class);
            if (decoded != null) {
                return decoded;
            }
        } catch (Exception e) {
            // If all else fails, it's a plain, unquoted string.
            return rawNbtString;
        }
        // Fallback to the raw string if parsing fails unexpectedly
        return rawNbtString;
    }




}