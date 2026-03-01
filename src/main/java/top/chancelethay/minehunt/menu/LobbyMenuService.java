package top.chancelethay.minehunt.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.GameWorldManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.stats.StatsService;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.utils.Tasks;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public final class LobbyMenuService implements Listener {
    private final Settings settings;
    private final MessageService msg;
    private final GameManager gameManager;
    private final GameWorldManager worldManager;
    private final PlayerRoleManager roleManager;
    private final StatsService statsService;
    private final Tasks tasks;
    private final MenuConfig menuConfig;
    private final NamespacedKey menuItemKey;

    private final Map<UUID, MenuSession> sessions = new HashMap<>();
    private final Map<UUID, Long> openCooldown = new HashMap<>();
    private BukkitTask globalUpdateTask;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();

    public LobbyMenuService(Plugin plugin,
                            Settings settings,
                            MessageService msg,
                            GameManager gameManager,
                            GameWorldManager worldManager,
                            PlayerRoleManager roleManager,
                            StatsService statsService,
                            Tasks tasks) {
        this.settings = settings;
        this.msg = msg;
        this.gameManager = gameManager;
        this.worldManager = worldManager;
        this.roleManager = roleManager;
        this.statsService = statsService;
        this.tasks = tasks;
        this.menuConfig = new MenuConfig(plugin);
        this.menuItemKey = new NamespacedKey(plugin, "lobby_menu_item");
        
        restartGlobalUpdateTask();
    }

    public void shutdown() {
        if (globalUpdateTask != null) {
            tasks.cancel(globalUpdateTask);
            globalUpdateTask = null;
        }
        sessions.clear();
        openCooldown.clear();
    }

    public void reload() {
        menuConfig.reload();
        restartGlobalUpdateTask();
    }

    private void restartGlobalUpdateTask() {
        if (globalUpdateTask != null) {
            tasks.cancel(globalUpdateTask);
        }
        int ticks = Math.max(5, menuConfig.getConfig().getInt("menu.updateTicks", 20));
        globalUpdateTask = tasks.repeat(() -> {
            for (Map.Entry<UUID, MenuSession> entry : sessions.entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p == null || !p.isOnline()) continue;
                if (p.getOpenInventory() == null) continue;
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top == null || top.getHolder() == null || !(top.getHolder() instanceof MenuHolder)) continue;
                populateMenu(top, entry.getValue().type, p);
            }
        }, ticks);
    }

    public void openMain(Player p) {
        openMenu(p, MenuType.MAIN);
    }

    public void giveLobbyItem(Player p) {
        FileConfiguration cfg = menuConfig.getConfig();
        if (!cfg.getBoolean("menu.lobbyItem.enabled", true)) return;
        int slot = clampSlot(cfg.getInt("menu.lobbyItem.slot", 4));
        ItemStack item = buildItem(cfg.getConfigurationSection("menu.lobbyItem"), p, placeholderMap(p), true);
        if (item == null) return;
        p.getInventory().setItem(slot, item);
    }

    public void removeLobbyItem(Player p) {
        if (p == null) return;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isLobbyMenuItem(it)) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private void openMenu(Player p, MenuType type) {
        if (!p.hasPermission("minehunt.menu")) {
            msg.send(p, "guard.no.perm");
            playDenySound(p);
            return;
        }
        if (menuConfig.getConfig().getBoolean("menu.openInLobbyOnly", true)) {
            if (!isLobby(p.getWorld())) {
                msg.send(p, "menu.disabled");
                playDenySound(p);
                return;
            }
        }

        boolean isNavigating = sessions.containsKey(p.getUniqueId());

        if (!isNavigating) {
            long now = System.currentTimeMillis();
            long cd = Math.max(0L, menuConfig.getConfig().getLong("menu.cooldownMillis", 1000L));
            Long last = openCooldown.get(p.getUniqueId());
            if (last != null && now - last < cd) {
                long left = (cd - (now - last) + 999L) / 1000L;
                msg.send(p, "menu.cooldown", left);
                playDenySound(p);
                return;
            }
            openCooldown.put(p.getUniqueId(), now);
        }

        String title = resolveText(menuConfig.getConfig().getString("menus." + type.key + ".title"));
        int size = normalizeSize(menuConfig.getConfig().getInt("menus." + type.key + ".size", 54));
        MenuHolder holder = new MenuHolder(type);

        Component titleComp = title == null ? Component.empty() : legacySerializer.deserialize(title);
        Inventory inv = Bukkit.createInventory(holder, size, titleComp);

        holder.setInventory(inv);
        populateMenu(inv, type, p);
        p.openInventory(inv);
        playOpenSound(p);
        sessions.put(p.getUniqueId(), new MenuSession(type));
    }

    private void populateMenu(Inventory inv, MenuType type, Player p) {
        Map<String, String> placeholders = placeholderMap(p);

        ConfigurationSection decorSection = menuConfig.getConfig().getConfigurationSection("menus." + type.key + ".decorations");
        if (decorSection != null) {
            for (String decorKey : decorSection.getKeys(false)) {
                ConfigurationSection itemSec = decorSection.getConfigurationSection(decorKey);
                if (itemSec == null) continue;
                int slot = itemSec.getInt("slot", -1);
                if (slot >= 0 && slot < inv.getSize()) {
                    ItemStack item = buildItem(itemSec, p, placeholders, false);
                    if (item != null) inv.setItem(slot, item);
                }
            }
        }

        ConfigurationSection actions = menuConfig.getConfig().getConfigurationSection("menus." + type.key + ".actions");
        if (actions != null) {
            for (String actionKey : actions.getKeys(false)) {
                ConfigurationSection itemSec = actions.getConfigurationSection(actionKey);
                if (itemSec == null) continue;
                int slot = itemSec.getInt("slot", -1);
                if (slot >= 0 && slot < inv.getSize()) {
                    ItemStack item = buildItem(itemSec, p, placeholders, true);
                    if (item != null) inv.setItem(slot, item);
                }
            }
        }

        if (type == MenuType.JOIN) {
            ConfigurationSection rooms = menuConfig.getConfig().getConfigurationSection("menus.join.rooms");
            if (rooms != null) {
                for (String roomKey : rooms.getKeys(false)) {
                    ConfigurationSection roomSec = rooms.getConfigurationSection(roomKey);
                    if (roomSec == null) continue;
                    int slot = roomSec.getInt("slot", -1);
                    if (slot >= 0 && slot < inv.getSize()) {
                        ItemStack item = buildItem(roomSec, p, placeholders, true);
                        if (item != null) inv.setItem(slot, item);
                    }
                }
            }
        }
    }

    private ItemStack buildItem(ConfigurationSection itemSec, Player p, Map<String, String> placeholders, boolean tagActions) {
        if (itemSec == null) return null;
        String materialName = itemSec.getString("material", "STONE");
        int data = itemSec.getInt("data", 0);
        int amount = Math.max(1, itemSec.getInt("amount", 1));
        Material mat = resolveMaterial(materialName, data);
        if (mat == null) return null;

        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (data > 0 && meta instanceof Damageable damageable) {
                damageable.setDamage(data);
            }

            String name = resolveText(itemSec.getString("name"));
            if (name != null) {
                String appliedName = applyPlaceholders(name, placeholders);
                Component nameComp = legacySerializer.deserialize(appliedName).decoration(TextDecoration.ITALIC, false);
                meta.displayName(nameComp);
            }

            List<String> loreRaw = itemSec.getStringList("lore");
            if (loreRaw != null && !loreRaw.isEmpty()) {
                List<Component> lore = loreRaw.stream()
                        .map(this::resolveText)
                        .map(text -> applyPlaceholders(text, placeholders))
                        .filter(text -> text != null && !text.isEmpty())
                        .map(legacySerializer::deserialize)
                        .map(comp -> comp.decoration(TextDecoration.ITALIC, false))
                        .collect(Collectors.toList());
                meta.lore(lore);
            }

            String skullOwner = itemSec.getString("skullOwner");
            if (skullOwner != null && meta instanceof SkullMeta skullMeta) {
                String owner = applyPlaceholders(skullOwner, placeholders);
                if (owner != null && !owner.isEmpty()) {
                    if (owner.equalsIgnoreCase(p.getName())) {
                        skullMeta.setOwningPlayer(p);
                    } else {
                        Player target = Bukkit.getPlayerExact(owner);
                        if (target != null) {
                            skullMeta.setOwningPlayer(target);
                        } else {
                            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                        }
                    }
                }
                meta = skullMeta;
            }

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);

            if (tagActions) {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(menuItemKey, PersistentDataType.STRING, "menu");
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Map<String, String> placeholderMap(Player p) {
        Map<String, String> map = new HashMap<>();
        GameState state = gameManager.getState();
        int online = Bukkit.getOnlinePlayers().size();
        int runners = roleManager.countAliveRunners();
        int hunters = roleManager.countAliveHunters();
        int spectators = roleManager.countSpectators();
        int participants = runners + hunters;

        boolean resetting = worldManager.isResetting();
        boolean ending = state == GameState.ENDED && !resetting;
        boolean running = state == GameState.RUNNING;
        String stateText;
        if (resetting) {
            stateText = msg.tr("menu.state.resetting");
        } else if (ending) {
            stateText = msg.tr("menu.state.ending");
        } else if (running) {
            stateText = msg.tr("menu.state.running");
        } else {
            stateText = msg.tr("menu.state.waiting");
        }
        map.put("state", stateText);
        map.put("state_raw", state.name());
        map.put("online", String.valueOf(online));
        map.put("participants", String.valueOf(participants));
        map.put("runners", String.valueOf(runners));
        map.put("hunters", String.valueOf(hunters));
        map.put("spectators", String.valueOf(spectators));
        map.put("locked", msg.tr(gameManager.isRolesLocked() ? "menu.locked.true" : "menu.locked.false"));

        int countdown = gameManager.getCountdownLeft();
        map.put("countdown", countdown > 0 ? String.valueOf(countdown) : "-");

        long startMillis = gameManager.getRoundStartMillis();
        map.put("round_time", formatDuration(startMillis));

        World gw = Bukkit.getWorld(settings.gameWorld);
        Difficulty diff = gw != null ? gw.getDifficulty() : null;
        map.put("mode", diff == null ? msg.tr("menu.mode.unknown") : msg.tr("menu.mode." + diff.name().toLowerCase(Locale.ROOT)));

        map.put("next_preparing", String.valueOf(worldManager.isNextPreparing()));
        map.put("next_ready", String.valueOf(worldManager.isNextReady()));
        map.put("next_progress", String.valueOf(worldManager.getNextProgressPercent()));
        map.put("player", p.getName());

        if (running) {
            map.put("latejoin_line", msg.tr("menu.status.line.latejoin", msg.tr(gameManager.isLateJoinAllowed() ? "menu.latejoin.true" : "menu.latejoin.false")));
        } else {
            map.put("latejoin_line", "");
        }
        if (!ending && !resetting) {
            map.put("participants_line", msg.tr("menu.status.line.participants", participants));
        } else {
            map.put("participants_line", "");
        }

        StatsService.PlayerStats stats = statsService.getStats(p.getUniqueId());
        map.put("games", String.valueOf(stats.getGames()));
        map.put("runner_kills", String.valueOf(stats.getRunnerKills()));
        map.put("hunter_kills", String.valueOf(stats.getHunterKills()));
        map.put("runner_wins", String.valueOf(stats.getRunnerWins()));
        map.put("hunter_wins", String.valueOf(stats.getHunterWins()));

        List<String> recentLines = formatRecent(stats, 5);
        for (int i = 0; i < recentLines.size(); i++) {
            map.put("recent_" + (i + 1), recentLines.get(i));
        }
        for (int i = recentLines.size(); i < 5; i++) {
            map.put("recent_" + (i + 1), msg.tr("menu.stats.recent.empty"));
        }
        return map;
    }

    private List<String> formatRecent(StatsService.PlayerStats stats, int limit) {
        List<String> list = new ArrayList<>();
        int count = 0;
        for (StatsService.MatchRecord r : stats.getRecent()) {
            if (count >= limit) break;
            String time = Instant.ofEpochMilli(r.timeMillis).atZone(ZoneId.systemDefault()).format(TIME_FMT);
            String role = msg.tr("menu.stats.role." + r.role.name().toLowerCase(Locale.ROOT));
            String result = msg.tr("menu.stats.result." + r.result.name().toLowerCase(Locale.ROOT));
            list.add(msg.tr("menu.stats.recent.line", result, role, time));
            count++;
        }
        return list;
    }

    private String formatDuration(long startMillis) {
        if (startMillis <= 0) return "-";
        long sec = (System.currentTimeMillis() - startMillis) / 1000L;
        if (sec < 0) sec = 0;
        long m = sec / 60;
        long s = sec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private String resolveText(String raw) {
        if (raw == null) return null;
        if (raw.startsWith("lang:")) {
            String key = raw.substring("lang:".length());
            return msg.tr(key);
        }
        return MessageService.color(raw);
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null) return null;
        String out = text;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    private boolean isLobby(World w) {
        if (w == null) return false;
        return w.getName().equalsIgnoreCase(settings.lobbyWorld);
    }

    private int normalizeSize(int size) {
        int s = Math.max(9, Math.min(54, size));
        return ((s + 8) / 9) * 9;
    }

    private int clampSlot(int slot) {
        if (slot < 0) return 0;
        if (slot > 8) return 8;
        return slot;
    }

    private Material resolveMaterial(String name, int data) {
        if (name == null) return Material.STONE;
        String n = name.toUpperCase(Locale.ROOT);
        Material mat = Material.matchMaterial(n);
        if (mat != null) return mat;
        if (n.equals("PLAYER_HEAD")) {
            mat = Material.matchMaterial("SKULL_ITEM");
        } else if (n.equals("WHITE_BANNER")) {
            mat = Material.matchMaterial("BANNER");
        } else if (n.endsWith("STAINED_GLASS_PANE")) {
            mat = Material.matchMaterial("STAINED_GLASS_PANE");
        }
        return mat != null ? mat : Material.STONE;
    }

    private boolean isLobbyMenuItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(menuItemKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onMenuItemUse(PlayerInteractEvent e) {
        if (e.getItem() == null) return;
        if (!isLobbyMenuItem(e.getItem())) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        openMain(p);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory inv = e.getView().getTopInventory();
        if (inv == null || !(inv.getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;

        MenuType type = holder.type;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) return;

        String action = findAction(type, slot);
        if (action == null) return;
        if (!checkPermission(p, action)) {
            msg.send(p, "guard.no.perm");
            playDenySound(p);
            return;
        }
        handleAction(p, type, action);
        if (e.getClick() == ClickType.NUMBER_KEY) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (inv == null || !(inv.getHolder() instanceof MenuHolder)) return;
        sessions.remove(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    private void handleAction(Player p, MenuType type, String action) {
        switch (action) {
            case "open_main" -> openMenu(p, MenuType.MAIN);
            case "open_team" -> openMenu(p, MenuType.TEAM);
            case "back" -> openMenu(p, MenuType.MAIN);
            case "close" -> p.closeInventory();
            case "join_auto" -> handleJoin(p, null, false);
            case "join_runner" -> handleJoin(p, PlayerRole.RUNNER, false);
            case "join_hunter" -> handleJoin(p, PlayerRole.HUNTER, false);
            case "join_spectator" -> handleJoin(p, PlayerRole.SPECTATOR, true);
            default -> {
                if (action.startsWith("room:")) {
                    String roomKey = action.substring("room:".length());
                    String act = menuConfig.getConfig().getString("menus.join.rooms." + roomKey + ".action", "join_auto");
                    handleAction(p, type, act);
                }
            }
        }
        playClickSound(p);
    }

    private void handleJoin(Player p, PlayerRole requestedRole, boolean wantSpectator) {
        if (wantSpectator) {
            if (gameManager.getState() == GameState.RUNNING && roleManager.isParticipant(p.getUniqueId())) {
                PlayerRole currentRole = roleManager.getRole(p.getUniqueId());
                roleManager.eliminateAndCheckEnd(p.getUniqueId(), currentRole, p, p.getName());
                msg.send(p, "cmd.join.spectator.running");
                return;
            }
            roleManager.setRole(p, PlayerRole.SPECTATOR);
            GameState st = gameManager.getState();
            if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
                gameManager.onTeamsChanged();
                gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
                msg.send(p, "cmd.join.spectator.after_clear");
            } else if (st == GameState.RUNNING) {
                msg.send(p, "cmd.join.spectator.running");
            } else {
                msg.send(p, "cmd.join.spectator.ok");
            }
            tasks.later(() -> refreshLobbyItem(p), 2L);
            return;
        }

        if (gameManager.isRolesLocked()) {
            boolean canLateJoin = (gameManager.getState() == GameState.RUNNING)
                    && gameManager.isLateJoinAllowed()
                    && !roleManager.isParticipant(p.getUniqueId());
            if (!canLateJoin) {
                msg.send(p, "cmd.join.locked");
                return;
            }
        }

        if (requestedRole == PlayerRole.HUNTER || requestedRole == PlayerRole.RUNNER) {
            roleManager.setRole(p, requestedRole);
            gameManager.onTeamsChanged();
            gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
            msg.send(p, "cmd.join.ok", requestedRole.name());
            tasks.later(() -> refreshLobbyItem(p), 2L);
            return;
        }

        PlayerRole balanced = roleManager.pickBalancedRole();
        roleManager.setRole(p, balanced);
        gameManager.onTeamsChanged();
        gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
        msg.send(p, "cmd.join.ok", balanced.name());
        tasks.later(() -> refreshLobbyItem(p), 2L);
    }

    private void refreshLobbyItem(Player p) {
        if (p == null || !p.isOnline()) return;
        GameState st = gameManager.getState();
        if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
            if (isLobby(p.getWorld())) {
                giveLobbyItem(p);
            }
        }
    }

    private boolean checkPermission(Player p, String action) {
        FileConfiguration cfg = menuConfig.getConfig();
        String perm;
        if (action.startsWith("room:")) {
            String roomKey = action.substring("room:".length());
            perm = cfg.getString("menus.join.rooms." + roomKey + ".permission");
        } else {
            perm = cfg.getString("menu.permissions." + action);
        }
        return perm == null || perm.isEmpty() || p.hasPermission(perm);
    }

    private String findAction(MenuType type, int slot) {
        FileConfiguration cfg = menuConfig.getConfig();
        ConfigurationSection actions = cfg.getConfigurationSection("menus." + type.key + ".actions");
        if (actions != null) {
            for (String actionKey : actions.getKeys(false)) {
                int s = actions.getInt(actionKey + ".slot", -1);
                if (s == slot) return actionKey;
            }
        }
        if (type == MenuType.JOIN) {
            ConfigurationSection rooms = cfg.getConfigurationSection("menus.join.rooms");
            if (rooms != null) {
                for (String roomKey : rooms.getKeys(false)) {
                    int s = rooms.getInt(roomKey + ".slot", -1);
                    if (s == slot) return "room:" + roomKey;
                }
            }
        }
        return null;
    }

    private void playOpenSound(Player p) {
        playSound(p, menuConfig.getConfig().getString("menu.sounds.open", "UI_BUTTON_CLICK"));
    }

    private void playClickSound(Player p) {
        playSound(p, menuConfig.getConfig().getString("menu.sounds.click", "UI_BUTTON_CLICK"));
    }

    private void playDenySound(Player p) {
        playSound(p, menuConfig.getConfig().getString("menu.sounds.deny", "BLOCK_NOTE_BLOCK_BASS"));
    }

    private void playSound(Player p, String name) {
        if (p == null || name == null || name.isEmpty()) return;
        Sound sound = matchSound(name);
        if (sound == null) return;
        try {
            p.playSound(p.getLocation(), sound, 1f, 1f);
        } catch (Throwable ignored) {}
    }

    private Sound matchSound(String name) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
            return Registry.SOUNDS.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class MenuSession {
        private final MenuType type;

        private MenuSession(MenuType type) {
            this.type = type;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private Inventory inventory;

        private MenuHolder(MenuType type) {
            this.type = type;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum MenuType {
        MAIN("main"),
        JOIN("join"),
        TEAM("team");

        private final String key;

        MenuType(String key) {
            this.key = key;
        }
    }
}