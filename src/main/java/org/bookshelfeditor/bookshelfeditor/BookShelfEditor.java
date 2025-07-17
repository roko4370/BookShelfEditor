package org.bookshelfeditor.bookshelfeditor;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BookShelfEditor extends JavaPlugin implements Listener {

    private Javalin app;
    private BookshelfManager bookshelfManager;
    private PlayerDataManager playerDataManager;
    private final Gson gson = new Gson();

    // A thread-safe set to hold all connected WebSocket clients for live updates
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    // Track player online status
    private final Map<UUID, Boolean> playerOnlineStatus = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("BookShelfEditor starting up...");
        saveDefaultConfig();

        this.bookshelfManager = new BookshelfManager(this);
        this.playerDataManager = new PlayerDataManager(this);

        if (!getConfig().getBoolean("initial-scan-complete")) {
            getLogger().info("First run detected. Performing initial world scan for bookshelves...");
            this.bookshelfManager.initialScan();
            getConfig().set("initial-scan-complete", true);
            saveConfig();
            getLogger().info("Initial scan complete. Found and saved " + this.bookshelfManager.getAllBookshelves().size() + " bookshelves.");
        } else {
            getLogger().info("Loading existing bookshelf data from file...");
            this.bookshelfManager.loadBookshelves();
            getLogger().info("Successfully loaded " + this.bookshelfManager.getAllBookshelves().size() + " bookshelves.");
        }

        // Register all event listeners
        getServer().getPluginManager().registerEvents(new BookshelfListener(this, this.bookshelfManager), this);
        getServer().getPluginManager().registerEvents(this, this); // For player join/quit events

        // Initialize online player status
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerOnlineStatus.put(player.getUniqueId(), true);
        }

        this.app = Javalin.create(config -> {
            config.staticFiles.add("/web", io.javalin.http.staticfiles.Location.CLASSPATH);
        });

        // WebSocket endpoint for all live updates
        app.ws("/api/live-updates", ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                getLogger().info("Web client connected for live updates.");
                // Send current player status to new client
                broadcastPlayerListUpdate();
            });

            ws.onClose(ctx -> {
                clients.remove(ctx);
                getLogger().info("Web client disconnected.");
            });
        });

        this.app.exception(Exception.class, (e, ctx) -> {
            getLogger().log(Level.SEVERE, "An unhandled error occurred during an API request", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        });

        this.app.after(ctx -> {
            String path = ctx.path();
            if (path.startsWith("/api/")) {
                String responseBody = ctx.result() == null ? "[empty body]" : ctx.result();
                getLogger().info(
                        "[WEB-DEBUG] Response for " + path + "?_query=" + ctx.queryString() + " | " +
                                "Status: " + ctx.status() + " | " +
                                "Content-Type: " + ctx.contentType() + " | " +
                                "Body: " + responseBody
                );
            }
        });

        // REST API Endpoints
        app.get("/api/bookshelves", this::handleGetAllBookshelves);
        app.get("/api/bookshelf", this::handleGetSingleBookshelf);
        app.post("/api/bookshelf/book/edit", this::handleEditBookshelfBook); // NEW: Endpoint for editing books
        app.post("/api/player/book/edit", this::handleEditPlayerBook);
        app.post("/api/bookshelf/book/delete", this::handleDeleteBookshelfBook);
        app.post("/api/player/book/delete", this::handleDeletePlayerBook);
        app.post("/api/bookshelf/book/lock", this::handleLockBookshelfBook);
        app.post("/api/bookshelf/book/unlock", this::handleUnlockBookshelfBook);
        app.post("/api/bookshelf/book/add", this::handleAddBookshelfBook);
        app.post("/api/bookshelf/reorder", this::handleReorderBookshelf);   // NEW

        // Player inventory endpoints
        app.get("/api/players", this::handleGetAllPlayers);
        app.get("/api/player/books", this::handleGetPlayerBooks);
        app.get("/api/books/all", this::handleGetAllBooks);
        app.post("/api/player/book/lock", this::handleLockPlayerBook);
        app.post("/api/player/book/unlock", this::handleUnlockPlayerBook);
        app.post("/api/player/book/add",    this::handleAddPlayerBook);

        this.app.start(7070);
        getLogger().info("Web server is running on http://localhost:7070");
    }

    @Override
    public void onDisable() {
        getLogger().info("BookShelfEditor is shutting down.");

        if (this.app != null) {
            this.app.stop();
        }

        if (this.bookshelfManager != null) {
            // NEW: Gracefully shutdown the async save system
            this.bookshelfManager.shutdown();
            getLogger().info("Bookshelf data saved and manager shutdown complete.");
        }
    }

    public void sendUpdate(String type, Object payload) {
        String json = gson.toJson(Map.of("type", type, "data", payload));
        clients.forEach(c -> c.send(json));
    }

    // --- PLAYER EVENT HANDLERS ---

    private void handleLockBookshelfBook(Context ctx) {
        try {
            BookEditRequest request = gson.fromJson(ctx.body(), BookEditRequest.class);
            if (request.getWorld() == null || request.getSlot() < 0 || request.getSlot() > 5) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'world' or invalid 'slot'."));
                return;
            }
            ChiseledBookshelfInfo location = new ChiseledBookshelfInfo(
                    request.getWorld(), request.getX(), request.getY(), request.getZ()
            );
            CompletableFuture<Void> lockFuture = bookshelfManager.lockBookInBookshelf(location, request.getSlot());
            ctx.future(() -> lockFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Book locked successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Book lock request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleLockBookshelfBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }


    private void handleUnlockBookshelfBook(Context ctx) {
        try {
            BookEditRequest request = gson.fromJson(ctx.body(), BookEditRequest.class);
            if (request.getWorld() == null || request.getSlot() < 0 || request.getSlot() > 5) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'world' or invalid 'slot'."));
                return;
            }
            ChiseledBookshelfInfo location = new ChiseledBookshelfInfo(
                    request.getWorld(), request.getX(), request.getY(), request.getZ()
            );
            CompletableFuture<Void> unlockFuture = bookshelfManager.unlockBookInBookshelf(location, request.getSlot());
            ctx.future(() -> unlockFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Book unlocked successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Book unlock request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleUnlockBookshelfBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }

    /* ------------------------------------------------------------------
     * POST  /api/bookshelf/reorder
     * Body â†’ BookshelfReorderRequest
     * ------------------------------------------------------------------ */
    private void handleReorderBookshelf(Context ctx) {
        try {
            BookshelfReorderRequest req =
                    gson.fromJson(ctx.body(), BookshelfReorderRequest.class);

            // basic sanity checks
            if (req.getWorld() == null ||
                    req.getNewOrder() == null || req.getNewOrder().size() != 6) {
                ctx.status(400).json(Map.of(
                        "error", "Body must contain 'world', coords and a 6-element 'newOrder'."));
                return;
            }

            ChiseledBookshelfInfo loc = new ChiseledBookshelfInfo(
                    req.getWorld(), req.getX(), req.getY(), req.getZ());

            CompletableFuture<Void> task =
                    bookshelfManager.reorderBooksInBookshelf(loc, req.getNewOrder());

            ctx.future(() -> task.thenRun(() ->
                            ctx.json(Map.of("success", true)))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    }));

        } catch (Exception e) {         // JSON parse or unexpected
            ctx.status(400).json(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }


    private void handleLockPlayerBook(Context ctx) {
        try {
            PlayerBookEditRequest request = gson.fromJson(ctx.body(), PlayerBookEditRequest.class);
            if (request.getPlayerName() == null || request.getSlot() < 0 || request.getSlot() > 40 || request.getInventoryType() == null) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'playerName', invalid 'slot', or missing 'inventoryType'."));
                return;
            }
            CompletableFuture<Void> lockFuture = playerDataManager.lockBookInPlayerInventory(
                    request.getPlayerName(), request.getSlot(), request.getInventoryType()
            );
            ctx.future(() -> lockFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Player book locked successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Player book lock request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleLockPlayerBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }

    private void handleUnlockPlayerBook(Context ctx) {
        try {
            PlayerBookEditRequest request = gson.fromJson(ctx.body(), PlayerBookEditRequest.class);
            if (request.getPlayerName() == null || request.getSlot() < 0 || request.getSlot() > 40 || request.getInventoryType() == null) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'playerName', invalid 'slot', or missing 'inventoryType'."));
                return;
            }
            CompletableFuture<Void> unlockFuture = playerDataManager.unlockBookInPlayerInventory(
                    request.getPlayerName(), request.getSlot(), request.getInventoryType()
            );
            ctx.future(() -> unlockFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Player book unlocked successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Player book unlock request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleUnlockPlayerBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }




    /**
     * Handles POST requests to edit a book in a player's inventory.
     */
    private void handleEditPlayerBook(Context ctx) {
        try {
            // Deserialize the JSON request body into our PlayerBookEditRequest object
            PlayerBookEditRequest request = gson.fromJson(ctx.body(), PlayerBookEditRequest.class);

            // Basic validation of the request data
            if (request.getPlayerName() == null || request.getSlot() < 0 || request.getSlot() > 40 || request.getInventoryType() == null) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'playerName', invalid 'slot', or missing 'inventoryType'."));
                return;
            }

            // Call the async book editing method in the manager
            CompletableFuture<Void> editFuture = playerDataManager.editBookInPlayerInventory(
                    request.getPlayerName(),
                    request.getSlot(),
                    request.getTitle(),
                    request.getAuthor(),
                    request.getPages(),
                    request.getInventoryType()
            );

            // Let Javalin handle the asynchronous operation result
            ctx.future(() -> editFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Player book edited successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Player book edit request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleEditPlayerBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }

    /* ------------------------------------------------------------------
     * POST  /api/bookshelf/book/add
     * Body â†’ BookAddRequest
     * ------------------------------------------------------------------ */
    private void handleAddBookshelfBook(Context ctx) {
        try {
            BookAddRequest req = gson.fromJson(ctx.body(), BookAddRequest.class);
            if (req.getWorld() == null) {
                ctx.status(400).json(Map.of("error", "Missing 'world'."));
                return;
            }

            ChiseledBookshelfInfo loc = new ChiseledBookshelfInfo(
                    req.getWorld(), req.getX(), req.getY(), req.getZ());

            CompletableFuture<Void> f = bookshelfManager.addBookToBookshelf(
                    loc, req.getSlot(),
                    req.getTitle(), req.getAuthor(), req.getPages());

            ctx.future(() -> f.thenRun(() -> ctx.json(Map.of("success", true)))
                    .exceptionally(ex -> {
                        ctx.status(500).json(Map.of("error", ex.getCause().getMessage()));
                        return null;
                    }));
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON."));
        }
    }

    /* ------------------------------------------------------------------
     * POST  /api/player/book/add
     * Body â†’ PlayerBookAddRequest
     * ------------------------------------------------------------------ */
    private void handleAddPlayerBook(Context ctx) {
        try {
            PlayerBookAddRequest req = gson.fromJson(ctx.body(), PlayerBookAddRequest.class);
            if (req.getPlayerName() == null) {
                ctx.status(400).json(Map.of("error", "Missing 'playerName'."));
                return;
            }

            String invType = req.getInventoryType() != null ? req.getInventoryType() : "INVENTORY";

            CompletableFuture<Void> f = playerDataManager.addBookToPlayerInventory(
                    req.getPlayerName(), invType,
                    req.getTitle(), req.getAuthor(), req.getPages());

            ctx.future(() -> f.thenRun(() -> ctx.json(Map.of("success", true)))
                    .exceptionally(ex -> {
                        ctx.status(500).json(Map.of("error", ex.getCause().getMessage()));
                        return null;
                    }));
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON."));
        }
    }


    private void handleDeleteBookshelfBook(Context ctx) {
        try {
            BookEditRequest request = gson.fromJson(ctx.body(), BookEditRequest.class);
            if (request.getWorld() == null || request.getSlot() < 0 || request.getSlot() > 5) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'world' or invalid 'slot'."));
                return;
            }
            ChiseledBookshelfInfo location = new ChiseledBookshelfInfo(
                    request.getWorld(), request.getX(), request.getY(), request.getZ()
            );
            CompletableFuture<Void> deleteFuture = bookshelfManager.deleteBookInBookshelf(location, request.getSlot());
            ctx.future(() -> deleteFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Book deleted successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Book delete request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleDeleteBookshelfBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }

    private void handleDeletePlayerBook(Context ctx) {
        try {
            PlayerBookEditRequest request = gson.fromJson(ctx.body(), PlayerBookEditRequest.class);
            if (request.getPlayerName() == null || request.getSlot() < 0 || request.getSlot() > 40 || request.getInventoryType() == null) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'playerName', invalid 'slot', or missing 'inventoryType'."));
                return;
            }
            CompletableFuture<Void> deleteFuture = playerDataManager.deleteBookInPlayerInventory(
                    request.getPlayerName(), request.getSlot(), request.getInventoryType()
            );
            ctx.future(() -> deleteFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Player book deleted successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Player book delete request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );
        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleDeletePlayerBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }



    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerOnlineStatus.put(player.getUniqueId(), true);
        getLogger().info("Player " + player.getName() + " joined - updating online status");

        // Broadcast player status update
        getServer().getScheduler().runTaskLater(this, () -> {
            broadcastPlayerStatusUpdate(player.getName(), player.getUniqueId().toString(), true);
            broadcastPlayerListUpdate();
        }, 5L); // Small delay to ensure player is fully loaded
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        playerOnlineStatus.put(player.getUniqueId(), false);
        getLogger().info("Player " + player.getName() + " quit - updating online status");

        // Broadcast player status update
        broadcastPlayerStatusUpdate(player.getName(), player.getUniqueId().toString(), false);
        broadcastPlayerListUpdate();
    }

    // --- BROADCAST METHODS ---

    public void broadcastBookshelfAdded(ChiseledBookshelfInfo info) {
        Map<String, Object> message = Map.of("type", "bookshelf-added", "data", info);
        String jsonMessage = gson.toJson(message);
        clients.forEach(client -> client.send(jsonMessage));
        getLogger().info("Broadcasted add for bookshelf at: " + info.getX() + ", " + info.getY() + ", " + info.getZ());
    }

    public void broadcastBookshelfRemoved(ChiseledBookshelfInfo info) {
        Map<String, Object> message = Map.of("type", "bookshelf-removed", "data", info);
        String jsonMessage = gson.toJson(message);
        clients.forEach(client -> client.send(jsonMessage));
        getLogger().info("Broadcasted remove for bookshelf at: " + info.getX() + ", " + info.getY() + ", " + info.getZ());
    }

    public void broadcastBookshelfUpdate(Block block) {
        if (block.getWorld() == null) return;

        Map<String, Object> locationData = Map.of(
                "world", block.getWorld().getName(),
                "x", block.getX(),
                "y", block.getY(),
                "z", block.getZ()
        );

        Map<String, Object> message = Map.of("type", "bookshelf-updated", "data", locationData);
        String jsonMessage = gson.toJson(message);
        clients.forEach(client -> client.send(jsonMessage));
    }

    public void broadcastPlayerBookUpdate(String playerName, String playerUuid) {
        Map<String, Object> playerData = Map.of(
                "playerName", playerName,
                "playerUuid", playerUuid
        );

        Map<String, Object> message = Map.of("type", "player-book-updated", "data", playerData);
        String jsonMessage = gson.toJson(message);
        clients.forEach(client -> client.send(jsonMessage));
        getLogger().info("Broadcasted book update for player: " + playerName);
    }

    public void broadcastPlayerStatusUpdate(String playerName, String playerUuid, boolean online) {
        Map<String, Object> playerData = Map.of(
                "playerName", playerName,
                "playerUuid", playerUuid,
                "online", online
        );

        Map<String, Object> message = Map.of("type", "player-status-updated", "data", playerData);
        String jsonMessage = gson.toJson(message);
        clients.forEach(client -> client.send(jsonMessage));
        getLogger().info("Broadcasted status update for player: " + playerName + " (" + (online ? "online" : "offline") + ")");
    }

    public void broadcastPlayerListUpdate() {
        // Send full player list update
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<Map<String, Object>> players = getAllPlayersWithStatus();
                Map<String, Object> message = Map.of("type", "player-list-updated", "data", players);
                String jsonMessage = gson.toJson(message);
                clients.forEach(client -> client.send(jsonMessage));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to broadcast player list update", e);
            }
        });
    }

    // --- REST HANDLER METHODS ---

    private void handleGetAllBookshelves(Context ctx) {
        List<ChiseledBookshelfInfo> sortedList = bookshelfManager.getAllBookshelves()
                .stream()
                .sorted(Comparator.comparing(ChiseledBookshelfInfo::getWorld)
                        .thenComparingInt(ChiseledBookshelfInfo::getX)
                        .thenComparingInt(ChiseledBookshelfInfo::getY)
                        .thenComparingInt(ChiseledBookshelfInfo::getZ))
                .toList();
        ctx.json(sortedList);
    }

    private void handleGetSingleBookshelf(Context ctx) {
        final String worldName = ctx.queryParam("world");
        final Integer x = ctx.queryParamAsClass("x", Integer.class).getOrDefault(null);
        final Integer y = ctx.queryParamAsClass("y", Integer.class).getOrDefault(null);
        final Integer z = ctx.queryParamAsClass("z", Integer.class).getOrDefault(null);

        if (worldName == null || x == null || y == null || z == null) {
            ctx.status(400).json(Map.of("error", "Missing required query parameters."));
            return;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        ctx.future(() -> future.thenAccept(resultJson -> ctx.contentType("application/json").result(resultJson)));

        getServer().getScheduler().runTask(this, () -> {
            try {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    future.complete(gson.toJson(new ArrayList<>()));
                    return;
                }

                Block block = world.getBlockAt(x, y, z);
                if (!(block.getState() instanceof ChiseledBookshelf bookshelf)) {
                    future.complete(gson.toJson(new ArrayList<>()));
                    return;
                }

                List<BookInfo> books = new ArrayList<>();
                ItemStack[] contents = bookshelf.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item != null && (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK)) {
                        ItemMeta rawMeta = item.getItemMeta();
                        if (rawMeta instanceof BookMeta meta) {
                            String bookTitle = "";
                            String bookAuthor = "";
                            List<String> bookPages = meta.hasPages() ? meta.getPages() : List.of("(This book is empty)");
                            if (item.getType() == Material.WRITABLE_BOOK) {
                                // Virtual for writable
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
                                // For signed books: Check for virtual metadata flag from lock
                                boolean isPlaceholder = meta.hasCustomModelData() && meta.getCustomModelData() == 1;
                                bookTitle = meta.hasTitle() ? (isPlaceholder && meta.getTitle().equalsIgnoreCase("Untitled") ? "" : meta.getTitle()) : "";
                                bookAuthor = meta.hasAuthor() ? (isPlaceholder && meta.getAuthor().equalsIgnoreCase("Unknown") ? "" : meta.getAuthor()) : "";
                            }
                            String bookType = item.getType().name();
                            books.add(new BookInfo(i, bookTitle, bookAuthor, bookPages, bookType));
                        }
                    }
                }
                future.complete(gson.toJson(books));

            } catch (Exception e) {
                future.completeExceptionally(e);
            }

        });
    }

    /**
     * NEW: Handles POST requests to edit a book in a chiseled bookshelf.
     */
    private void handleEditBookshelfBook(Context ctx) {
        try {
            // Deserialize the JSON request body into our BookEditRequest object
            BookEditRequest request = gson.fromJson(ctx.body(), BookEditRequest.class);

            // Basic validation of the request data
            if (request.getWorld() == null || request.getSlot() < 0 || request.getSlot() > 5) {
                ctx.status(400).json(Map.of("error", "Invalid request body. Missing 'world' or invalid 'slot'."));
                return;
            }

            ChiseledBookshelfInfo location = new ChiseledBookshelfInfo(
                    request.getWorld(), request.getX(), request.getY(), request.getZ()
            );

            // Call the async book editing method in the manager
            CompletableFuture<Void> editFuture = bookshelfManager.editBookInBookshelf(
                    location,
                    request.getSlot(),
                    request.getTitle(),
                    request.getAuthor(),
                    request.getPages()
            );

            // Let Javalin handle the asynchronous operation result
            ctx.future(() -> editFuture
                    .thenRun(() -> ctx.status(200).json(Map.of("success", true, "message", "Book edited successfully.")))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        getLogger().log(Level.WARNING, "Book edit request failed: " + cause.getMessage(), cause);
                        ctx.status(500).json(Map.of("error", cause.getMessage()));
                        return null;
                    })
            );

        } catch (JsonSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON format in request body."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred in handleEditBookshelfBook", e);
            ctx.status(500).json(Map.of("error", "An internal server error occurred."));
        }
    }

    private void handleGetAllPlayers(Context ctx) {
        CompletableFuture<String> future = new CompletableFuture<>();
        ctx.future(() -> future.thenAccept(resultJson -> ctx.contentType("application/json").result(resultJson)));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<Map<String, Object>> players = getAllPlayersWithStatus();
                future.complete(gson.toJson(players));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }

    private void handleGetPlayerBooks(Context ctx) {
        final String playerName = ctx.queryParam("player");
        final String inventoryType = ctx.queryParam("type");
        final String resolvedInventoryType = inventoryType != null ? inventoryType : "INVENTORY";

        if (playerName == null) {
            ctx.status(400).json(Map.of("error", "Missing player parameter."));
            return;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        ctx.future(() -> future.thenAccept(resultJson -> ctx.contentType("application/json").result(resultJson)));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<PlayerBookInfo> books = playerDataManager.getPlayerBooks(playerName, resolvedInventoryType);
                future.complete(gson.toJson(books));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }

    private void handleGetAllBooks(Context ctx) {
        CompletableFuture<String> future = new CompletableFuture<>();
        ctx.future(() -> future.thenAccept(resultJson -> ctx.contentType("application/json").result(resultJson)));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<PlayerBookInfo> books = playerDataManager.getAllPlayersBooks();
                future.complete(gson.toJson(books));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }

    // --- HELPER METHODS ---

    private List<Map<String, Object>> getAllPlayersWithStatus() {
        List<Map<String, Object>> players = new ArrayList<>();

        // Add online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(Map.of(
                    "name", player.getName(),
                    "uuid", player.getUniqueId().toString(),
                    "online", true,
                    "lastSeen", System.currentTimeMillis()
            ));
        }

        // Add offline players who have played before
        for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
                players.add(Map.of(
                        "name", offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown",
                        "uuid", offlinePlayer.getUniqueId().toString(),
                        "online", false,
                        "lastSeen", offlinePlayer.getLastPlayed()
                ));
            }
        }

        return players;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }
}