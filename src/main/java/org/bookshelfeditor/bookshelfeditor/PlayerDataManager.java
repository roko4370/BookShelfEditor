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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerDataManager {
    private final JavaPlugin plugin;

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

            // Read file into ReadWriteNBT so getCompoundList() returns ReadWriteNBTCompoundList
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
                if (item.hasTag("components")) {
                    ReadWriteNBT components = item.getCompound("components");
                    boolean isPlaceholder = components.hasTag("custom_data") && components.getCompound("custom_data").getBoolean("isPlaceholder");
                    if (itemId.equals("minecraft:writable_book")) {
                        if (components.hasTag("minecraft:custom_name")) {
                            bookTitle = components.getString("minecraft:custom_name").replaceAll("[\"{}]", "");
                        }
                        if (components.hasTag("minecraft:lore") && components.getCompoundList("minecraft:lore").size() > 0) {
                            String loreText = components.getCompoundList("minecraft:lore").get(0).getString("text").replaceAll("[\"{}]", "");
                            if (loreText.startsWith("by ")) {
                                bookAuthor = loreText.substring(3);
                            }
                        }
                        if (components.hasTag("minecraft:writable_book_content")) {
                            ReadWriteNBT bookContent = components.getCompound("minecraft:writable_book_content");
                            if (bookContent.hasTag("pages")) {
                                ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                                for (ReadWriteNBT page : pagesList) {
                                    if (page.hasTag("raw")) {
                                        bookPages.add(page.getString("raw"));
                                    }
                                }
                            }
                        }
                    } else {
                        if (components.hasTag("minecraft:written_book_content")) {
                            ReadWriteNBT bookContent = components.getCompound("minecraft:written_book_content");
                            bookTitle = bookContent.hasTag("title") ? (isPlaceholder && bookContent.getCompound("title").getString("raw").equalsIgnoreCase("Untitled") ? "" : bookContent.getCompound("title").getString("raw")) : "";
                            bookAuthor = bookContent.hasTag("author") ? (isPlaceholder && bookContent.getString("author").equalsIgnoreCase("Unknown") ? "" : bookContent.getString("author")) : "";
                            if (bookContent.hasTag("pages")) {
                                ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                                for (ReadWriteNBT page : pagesList) {
                                    if (page.hasTag("raw")) {
                                        bookPages.add(page.getString("raw"));
                                    }
                                }
                            }
                        }
                    }
                }
                if (bookPages.isEmpty()) {
                    bookPages.add("(This book is empty)");
                }
                String bookType = itemId.equals("minecraft:writable_book") ? "WRITABLE_BOOK" : "WRITTEN_BOOK";
                books.add(new PlayerBookInfo(slot, bookTitle, bookAuthor, bookPages, playerName, playerUuid.toString(), false, inventoryType, bookType));


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
    public CompletableFuture<Void> editBookInPlayerInventory(String playerName, int slot, String title, String author, List<String> pages, String inventoryType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType) ? onlinePlayer.getEnderChest() : onlinePlayer.getInventory();
                    ItemStack existingItem = inv.getItem(slot);
                    if (existingItem == null || (existingItem.getType() != Material.WRITTEN_BOOK && existingItem.getType() != Material.WRITABLE_BOOK)) {
                        future.completeExceptionally(new IllegalStateException("No book in slot " + slot));
                        return;
                    }
                    Material bookType = existingItem.getType();
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
                    ReadWriteNBT targetItem = null;
                    for (ReadWriteNBT item : inventory) {
                        if (item.getByte("Slot") == slot) {
                            String itemId = item.getString("id");
                            if (itemId.equals("minecraft:written_book") || itemId.equals("minecraft:writable_book")) {
                                targetItem = item;
                                break;
                            }
                        }
                    }
                    if (targetItem == null) {
                        future.completeExceptionally(new IllegalStateException("No book in slot " + slot));
                        return;
                    }
                    String bookType = targetItem.getString("id");
                    ReadWriteNBT components = targetItem.getOrCreateCompound("components");

                    // Handle title: Preserve empty if no input; allow literal "Untitled"
                    String finalTitle = (title != null && !title.trim().isEmpty()) ? title : "";
                    if (bookType.equals("minecraft:written_book")) {
                        ReadWriteNBT writtenContent = components.getOrCreateCompound("minecraft:written_book_content");
                        if (!finalTitle.isEmpty()) {
                            writtenContent.getOrCreateCompound("title").setString("raw", finalTitle);
                        } else {
                            writtenContent.removeKey("title");
                        }
                    } else {
                        if (!finalTitle.isEmpty()) {
                            components.setString("minecraft:custom_name", "{\"text\":\"" + finalTitle + "\"}");
                        } else {
                            components.removeKey("minecraft:custom_name");
                        }
                    }

                    // Handle author: Preserve empty if no input; allow literal "Unknown"
                    String finalAuthor = (author != null && !author.trim().isEmpty()) ? author : "";
                    if (bookType.equals("minecraft:written_book")) {
                        ReadWriteNBT writtenContent = components.getOrCreateCompound("minecraft:written_book_content");
                        if (!finalAuthor.isEmpty()) {
                            writtenContent.setString("author", finalAuthor);
                        } else {
                            writtenContent.removeKey("author");
                        }
                    } else {
                        if (!finalAuthor.isEmpty()) {
                            ReadWriteNBTCompoundList loreList = components.getCompoundList("minecraft:lore");
                            loreList.clear();
                            loreList.addCompound().setString("text", "{\"text\":\"by " + finalAuthor + "\",\"color\":\"gray\",\"italic\":false}");
                            loreList.addCompound().setString("text", "{\"text\":\"Original\",\"color\":\"gray\",\"italic\":false}");
                        } else {
                            components.removeKey("minecraft:lore");
                        }
                    }

                    // Update pages
                    ReadWriteNBT content = bookType.equals("minecraft:writable_book") ? components.getOrCreateCompound("minecraft:writable_book_content") : components.getOrCreateCompound("minecraft:written_book_content");
                    ReadWriteNBTCompoundList pagesList = content.getCompoundList("pages");
                    pagesList.clear();
                    for (String page : pages) {
                        ReadWriteNBT pageComp = pagesList.addCompound();
                        pageComp.setString("raw", page);
                    }

                    NBT.writeFile(playerDataFile, playerData);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }



    public CompletableFuture<Void> lockBookInPlayerInventory(String playerName, int slot, String inventoryType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
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
                    newMeta.setTitle(title); // Preserve empty as empty
                    newMeta.setAuthor(author); // Preserve empty as empty
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
                    ReadWriteNBT targetItem = null;
                    for (ReadWriteNBT item : inventory) {
                        if (item.getByte("Slot") == slot) {
                            String itemId = item.getString("id");
                            if (itemId.equals("minecraft:writable_book")) {
                                targetItem = item;
                                break;
                            }
                        }
                    }
                    if (targetItem == null) {
                        future.completeExceptionally(new IllegalStateException("No writable book in slot " + slot + " to lock."));
                        return;
                    }
                    String title = "";
                    String author = "";
                    ReadWriteNBT components = targetItem.getOrCreateCompound("components");
                    if (components.hasTag("minecraft:custom_name")) {
                        title = components.getString("minecraft:custom_name").replaceAll("[\"{}]", "");
                    }
                    if (components.hasTag("minecraft:lore") && components.getCompoundList("minecraft:lore").size() > 0) {
                        String loreText = components.getCompoundList("minecraft:lore").get(0).getString("text").replaceAll("[\"{}]", "");
                        if (loreText.startsWith("by ")) {
                            author = loreText.substring(3);
                        }
                    }
                    List<String> pages = new ArrayList<>();
                    if (components.hasTag("minecraft:writable_book_content")) {
                        ReadWriteNBT bookContent = components.getCompound("minecraft:writable_book_content");
                        if (bookContent.hasTag("pages")) {
                            ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                            for (ReadWriteNBT page : pagesList) {
                                if (page.hasTag("raw")) {
                                    pages.add(page.getString("raw"));
                                }
                            }
                        }
                    }
                    targetItem.setString("id", "minecraft:written_book");
                    components.removeKey("minecraft:custom_name");
                    components.removeKey("minecraft:lore");
                    components.removeKey("minecraft:writable_book_content");
                    ReadWriteNBT writtenContent = components.getOrCreateCompound("minecraft:written_book_content");
                    ReadWriteNBT titleComp = writtenContent.getOrCreateCompound("title");
                    titleComp.setString("raw", title); // Preserve empty as empty
                    writtenContent.setString("author", author); // Preserve empty as empty
                    ReadWriteNBTCompoundList pagesList = writtenContent.getCompoundList("pages");
                    pagesList.clear();
                    for (String page : pages) {
                        ReadWriteNBT pageComp = pagesList.addCompound();
                        pageComp.setString("raw", page);
                    }
                    writtenContent.setInteger("generation", 0);
                    NBT.writeFile(playerDataFile, playerData);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }



    public CompletableFuture<Void> unlockBookInPlayerInventory(String playerName, int slot, String inventoryType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.inventory.Inventory inv = "ENDERCHEST".equals(inventoryType) ? onlinePlayer.getEnderChest() : onlinePlayer.getInventory();
                    ItemStack existingItem = inv.getItem(slot);
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
                    ReadWriteNBT targetItem = null;
                    for (ReadWriteNBT item : inventory) {
                        if (item.getByte("Slot") == slot) {
                            String itemId = item.getString("id");
                            if (itemId.equals("minecraft:written_book")) {
                                targetItem = item;
                                break;
                            }
                        }
                    }
                    if (targetItem == null) {
                        future.completeExceptionally(new IllegalStateException("No written book in slot " + slot + " to unlock."));
                        return;
                    }
                    String title = "";
                    String author = "";
                    ReadWriteNBT components = targetItem.getOrCreateCompound("components");
                    if (components.hasTag("minecraft:written_book_content") && components.getCompound("minecraft:written_book_content").hasTag("title")) {
                        title = components.getCompound("minecraft:written_book_content").getCompound("title").getString("raw");
                    }
                    if (components.hasTag("minecraft:written_book_content") && components.getCompound("minecraft:written_book_content").hasTag("author")) {
                        author = components.getCompound("minecraft:written_book_content").getString("author");
                    }
                    List<String> pages = new ArrayList<>();
                    if (components.hasTag("minecraft:written_book_content")) {
                        ReadWriteNBT bookContent = components.getCompound("minecraft:written_book_content");
                        if (bookContent.hasTag("pages")) {
                            ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                            for (ReadWriteNBT page : pagesList) {
                                if (page.hasTag("raw")) {
                                    pages.add(page.getString("raw"));
                                }
                            }
                        }
                    }
                    targetItem.setString("id", "minecraft:writable_book");
                    components.removeKey("minecraft:written_book_content");
                    if (!title.isEmpty()) {
                        components.setString("minecraft:custom_name", "{\"text\":\"" + title + "\"}");
                    }
                    if (!author.isEmpty()) {
                        ReadWriteNBTCompoundList loreList = components.getCompoundList("minecraft:lore");
                        loreList.clear();
                        loreList.addCompound().setString("text", "{\"text\":\"by " + author + "\",\"color\":\"gray\",\"italic\":false}");
                        loreList.addCompound().setString("text", "{\"text\":\"Original\",\"color\":\"gray\",\"italic\":false}");
                    }
                    ReadWriteNBT writableContent = components.getOrCreateCompound("minecraft:writable_book_content");
                    ReadWriteNBTCompoundList pagesList = writableContent.getCompoundList("pages");
                    pagesList.clear();
                    for (String page : pages) {
                        ReadWriteNBT pageComp = pagesList.addCompound();
                        pageComp.setString("raw", page);
                    }
                    NBT.writeFile(playerDataFile, playerData);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
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




}