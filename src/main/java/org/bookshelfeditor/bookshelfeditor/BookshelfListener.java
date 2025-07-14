package org.bookshelfeditor.bookshelfeditor;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import java.util.List;

public class BookshelfListener implements Listener {

    private final BookShelfEditor plugin;
    private final BookshelfManager manager;

    public BookshelfListener(BookShelfEditor plugin, BookshelfManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() == Material.CHISELED_BOOKSHELF) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ChiseledBookshelfInfo newInfo = manager.addBookshelf(event.getBlock());
                if (newInfo != null) {
                    plugin.broadcastBookshelfAdded(newInfo);
                }
            });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.CHISELED_BOOKSHELF) {
            Location loc = event.getBlock().getLocation();
            if (loc.getWorld() == null) return;

            ChiseledBookshelfInfo removedInfo = new ChiseledBookshelfInfo(
                    loc.getWorld().getName(),
                    loc.getBlockX(),
                    loc.getBlockY(),
                    loc.getBlockZ()
            );

            manager.removeBookshelf(event.getBlock());
            plugin.broadcastBookshelfRemoved(removedInfo);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock != null && clickedBlock.getType() == Material.CHISELED_BOOKSHELF) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.broadcastBookshelfUpdate(clickedBlock);
            });
        }
    }

    @EventHandler
    public void onPlayerEditBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();

        // Check if the player is signing the book. This is where the override happens.
        if (event.isSigning()) {
            BookMeta previousMeta = event.getPreviousBookMeta();

            // Check if it's a "virtual author" book by looking for our specific lore.
            if (previousMeta.hasLore()) {
                List<Component> lore = previousMeta.lore();
                if (lore != null && lore.size() == 2 && PlainTextComponentSerializer.plainText().serialize(lore.get(1)).contains("Original")) {

                    // --- START OF THE OVERRIDE LOGIC ---

                    // 1. Extract the virtual author's name from the first line of the old lore.
                    String virtualAuthor = null;
                    if (lore.get(0) != null) {
                        String loreLine = PlainTextComponentSerializer.plainText().serialize(lore.get(0));
                        if (loreLine.startsWith("by ")) {
                            virtualAuthor = loreLine.substring(3); // Extracts the name after "by "
                        }
                    }

                    // 2. Extract the virtual title from the display name.
                    String virtualTitle = null;
                    if (previousMeta.displayName() != null) {
                        virtualTitle = PlainTextComponentSerializer.plainText().serialize(previousMeta.displayName());
                    }

                    // 3. Get the new metadata that the game is about to save.
                    BookMeta newMeta = event.getNewBookMeta();

                    // 4. If we successfully extracted a virtual author, OVERRIDE the author set by the game.
                    if (virtualAuthor != null && !virtualAuthor.isBlank()) {
                        newMeta.setAuthor(virtualAuthor);
                        plugin.getLogger().info("Virtual book signed by " + event.getPlayer().getName() + ". Overriding author to '" + virtualAuthor + "'.");
                    }

                    // 5. If we successfully extracted a virtual title, OVERRIDE the title set by the game.
                    if (virtualTitle != null && !virtualTitle.isBlank()) {
                        newMeta.setTitle(virtualTitle);
                        plugin.getLogger().info("Virtual book signed by " + event.getPlayer().getName() + ". Overriding title to '" + virtualTitle + "'.");
                    }

                    // 6. IMPORTANT: Clean up the virtual metadata (custom name and lore) to make it a standard WRITTEN_BOOK.
                    newMeta.displayName(null); // Remove the custom item name.
                    newMeta.lore(null); // Remove the virtual lore ("by..." and "Original").

                    // 7. Apply our modified metadata back to the event.
                    event.setNewBookMeta(newMeta);

                    // --- END OF THE OVERRIDE LOGIC ---
                }
            }
        }

            // This must run for ALL book edits (page edits, signing) to keep the web UI updated.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                plugin.broadcastPlayerBookUpdate(event.getPlayer().getName(), event.getPlayer().getUniqueId().toString());
            }, 5L);
        }


        @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            ItemStack currentItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();

            // Check if the event involves books
            if (isBook(currentItem) || isBook(cursorItem)) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    plugin.broadcastPlayerBookUpdate(player.getName(), player.getUniqueId().toString());
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // Check if any of the dragged items are books
            for (ItemStack item : event.getNewItems().values()) {
                if (isBook(item)) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        plugin.broadcastPlayerBookUpdate(player.getName(), player.getUniqueId().toString());
                    }, 2L);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            // Check if inventory contains books
            boolean hasBooks = false;
            for (ItemStack item : event.getInventory().getContents()) {
                if (isBook(item)) {
                    hasBooks = true;
                    break;
                }
            }

            if (hasBooks) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    plugin.broadcastPlayerBookUpdate(player.getName(), player.getUniqueId().toString());
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isBook(event.getItemDrop().getItemStack())) {
            Player player = event.getPlayer();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                plugin.broadcastPlayerBookUpdate(player.getName(), player.getUniqueId().toString());
            }, 2L);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (isBook(event.getItem().getItemStack())) {
            Player player = event.getPlayer();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                plugin.broadcastPlayerBookUpdate(player.getName(), player.getUniqueId().toString());
            }, 2L);
        }
    }

    private boolean isBook(ItemStack item) {
        return item != null && (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK);
    }
}