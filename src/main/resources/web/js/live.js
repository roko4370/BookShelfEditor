/* ---------- GLOBAL SINGLETON ---------- */
const ws = new WebSocket(`ws://${location.host}/api/live-updates`);
ws.addEventListener("message", e => {
    const msg = JSON.parse(e.data);
    switch (msg.type) {
        case "bookshelf-added":   onShelfAdded(msg.data);        break;
        case "bookshelf-removed": onShelfRemoved(msg.data);      break;
        case "bookshelf-updated": onShelfUpdated(msg.data);      break;
        case "player-status-updated": onPlayerStatus(msg.data);  break;
        case "player-list-updated":   renderPlayerTable(msg.data);break;
        case "player-book-updated":   onPlayerBooks(msg.data);   break;
    }
});
/* ---------- BOOKSHELF HANDLERS ---------- */
async function onShelfAdded(info){
    insertShelfRow(info);                      // add row to table
}
async function onShelfRemoved(info){
    removeShelfRow(info);                      // delete row
    maybeCloseShelfDetail(info);               // if user is viewing it
}
async function onShelfUpdated(info){
    updateShelfRowIcon(info);                  // update row icon(s)
    if(isShelfDetailOpen(info)){
        const books = await fetchShelfBooks(info);
        renderShelfDetail(books);
    }
}
/* ---------- PLAYER HANDLERS ---------- */
function onPlayerStatus({ playerName, playerUuid, online }) {
    const row = document.querySelector(
        `tr[data-player-uuid="${playerUuid}"]`);
    if (!row) return;                      // not in view yet

    row.querySelector('.online-indicator')
        .classList.toggle('online',  online);
    row.querySelector('.online-indicator')
        .classList.toggle('offline', !online);

    row.querySelector('.last-seen').textContent =
        online ? 'Now' : formatLastSeen(Date.now());
}
function onPlayerBooks({playerName,playerUuid}){
    if(currentPlayerUuid !== playerUuid) return;  // user not viewing
    reloadInventoryTab(playerName,"INVENTORY");
    reloadInventoryTab(playerName,"ENDERCHEST");
}
/* ---------- REST HELPERS ---------- */
function fetchShelfBooks({world,x,y,z}){
    const q = new URLSearchParams({world,x,y,z}).toString();
    return fetch(`/api/bookshelf?${q}`).then(r=>r.json());
}
function reloadInventoryTab(playerName,type){
    const q = new URLSearchParams({player:playerName,type}).toString();
    fetch(`/api/player/books?${q}`)
        .then(r=>r.json())
        .then(books=>renderInventory(type,books));
}
function renderPlayerTable(list) {
    playerTableBody.innerHTML = '';
    displayPlayers(list);          // reuse existing builder
    playerStatus.textContent = `Loaded ${list.length} players`;
}

/* ---------- HOOK INITIAL FULL LISTS ---------- */
ws.addEventListener("open", async () => {
    // fetch initial data once WebSocket is up
    renderPlayerTable(await (await fetch("/api/players")).json());
    renderShelfTable(await (await fetch("/api/bookshelves")).json());
});