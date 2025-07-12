package org.bookshelfeditor.bookshelfeditor;

import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBTCompoundList;
import org.bukkit.Bukkit;
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
                    String title = meta.hasTitle() ? meta.getTitle() : "Untitled";
                    String author = meta.hasAuthor() ? meta.getAuthor() : "Unknown";
                    List<String> pages = meta.hasPages() ? meta.getPages() : List.of("(This book is empty)");

                    books.add(new PlayerBookInfo(
                            i, title, author, pages,
                            player.getName(), player.getUniqueId().toString(),
                            true, inventoryType
                    ));
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
                String title = "Untitled";
                String author = "Unknown";
                List<String> pages = new ArrayList<>();

                if (item.hasTag("components")) {
                    ReadWriteNBT components = item.getCompound("components");

                    if (components.hasTag("minecraft:written_book_content")) {
                        ReadWriteNBT bookContent = components.getCompound("minecraft:written_book_content");
                        if (bookContent.hasTag("title")) {
                            ReadWriteNBT titleComp = bookContent.getCompound("title");
                            if (titleComp.hasTag("raw")) {
                                title = titleComp.getString("raw");
                            }
                        }
                        if (bookContent.hasTag("author")) {
                            author = bookContent.getString("author");
                        }
                        if (bookContent.hasTag("pages")) {
                            ReadWriteNBTCompoundList pagesList = bookContent.getCompoundList("pages");
                            for (ReadWriteNBT page : pagesList) {
                                if (page.hasTag("raw")) {
                                    pages.add(page.getString("raw"));
                                }
                            }
                        }
                    } else if (components.hasTag("minecraft:writable_book_content")) {
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
                } else if (item.hasTag("tag")) {
                    ReadWriteNBT tag = item.getCompound("tag");
                    if (tag.hasTag("title")) {
                        title = tag.getString("title");
                    }
                    if (tag.hasTag("author")) {
                        author = tag.getString("author");
                    }
                    if (tag.hasTag("pages")) {
                        var pagesList = tag.getStringList("pages");
                        // Fixed: iterate manually instead of using addAll()
                        for (String page : pagesList) {
                            pages.add(page);
                        }
                    }
                }


                if (pages.isEmpty()) {
                    pages.add("(This book is empty)");
                }

                books.add(new PlayerBookInfo(
                        slot, title, author, pages,
                        playerName, playerUuid.toString(),
                        false, inventoryType
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
    public CompletableFuture<Void> editBookInPlayerInventory(String playerName, int slot, String title, String author, List<String> pages, String inventoryType) {
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

                    Material bookType = existingItem.getType();
                    ItemStack newBook = new ItemStack(bookType);
                    BookMeta meta = (BookMeta) newBook.getItemMeta();
                    if (bookType == Material.WRITABLE_BOOK) {
                        // Virtual author for writable book
                        meta.displayName(Component.text(title));
                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("by " + author, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                        lore.add(Component.text("Original", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                        meta.lore(lore);
                    } else {
                        // Standard for written book
                        meta.setTitle(title);
                        meta.setAuthor(author);
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

                    String bookType = targetItem.getString("id").equals("minecraft:writable_book") ? "minecraft:writable_book" : "minecraft:written_book";
                    ReadWriteNBT components = targetItem.getOrCreateCompound("components");

                    if (bookType.equals("minecraft:writable_book")) {
                        // Virtual author for writable
                        ReadWriteNBT writableContent = components.getOrCreateCompound("minecraft:writable_book_content");
                        // CHANGE: Replaced getOrCreateCompoundList with getCompoundList
                        ReadWriteNBTCompoundList pagesList = writableContent.getCompoundList("pages");
                        pagesList.clear();
                        for (String page : pages) {
                            ReadWriteNBT pageComp = pagesList.addCompound();
                            pageComp.setString("raw", page);
                        }
                        // Virtual author via custom data (simulate lore/display)
                        components.setString("minecraft:custom_name", "{\"text\":\"" + title + "\"}");
                        // CHANGE: Replaced getOrCreateCompoundList with getCompoundList
                        ReadWriteNBTCompoundList loreList = components.getCompoundList("minecraft:lore");
                        loreList.clear();
                        loreList.addCompound().setString("text", "{\"text\":\"by " + author + "\",\"color\":\"gray\",\"italic\":false}");
                        loreList.addCompound().setString("text", "{\"text\":\"Original\",\"color\":\"gray\",\"italic\":false}");
                    } else {
                        // Standard for written
                        ReadWriteNBT writtenContent = components.getOrCreateCompound("minecraft:written_book_content");
                        writtenContent.setString("author", author);
                        ReadWriteNBT titleComp = writtenContent.getOrCreateCompound("title");
                        titleComp.setString("raw", title);
                        // CHANGE: Replaced getOrCreateCompoundList with getCompoundList
                        ReadWriteNBTCompoundList pagesList = writtenContent.getCompoundList("pages");
                        pagesList.clear();
                        for (String page : pages) {
                            ReadWriteNBT pageComp = pagesList.addCompound();
                            pageComp.setString("raw", page);
                        }
                    }

                    // CHANGE: Swapped parameters to match NBT.writeFile(File, ReadWriteNBT) signature
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