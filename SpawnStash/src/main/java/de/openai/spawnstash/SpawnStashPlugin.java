package de.openai.spawnstash;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class SpawnStashPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Map<UUID, Selection> selections = new HashMap<>();
    private File stashesFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stashesFolder = new File(getDataFolder(), "stashes");
        if (!stashesFolder.exists() && !stashesFolder.mkdirs()) {
            getLogger().warning("Der stashes-Ordner konnte nicht erstellt werden.");
        }

        Objects.requireNonNull(getCommand("spawnstash")).setExecutor(this);
        Objects.requireNonNull(getCommand("spawnstash")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("stash")).setExecutor(this);
        Objects.requireNonNull(getCommand("stash")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SpawnStash 2.0 aktiviert.");
    }

    @EventHandler
    public void onSelectionClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("spawnstash.edit")) return;
        if (!isOozingArrow(event.getItem())) return;

        event.setCancelled(true);
        Location clicked = event.getClickedBlock().getLocation();
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());

        if (selection.pos1 == null || selection.pos2 != null) {
            selection.pos1 = clicked;
            selection.pos2 = null;
            send(player, "&aPosition 1 gesetzt: &f" + format(clicked));
            send(player, "&7Rechtsklicke mit dem Arrow of Oozing auf die zweite Ecke.");
        } else {
            if (!Objects.equals(selection.pos1.getWorld(), clicked.getWorld())) {
                send(player, "&cBeide Positionen müssen in derselben Welt sein.");
                return;
            }
            selection.pos2 = clicked;
            long volume = volume(selection.pos1, selection.pos2);
            send(player, "&aPosition 2 gesetzt: &f" + format(clicked));
            send(player, "&7Auswahl: &f" + volume + " Blöcke&7. Jetzt: &e/stash save <name>");
        }
    }

    private boolean isOozingArrow(ItemStack item) {
        if (item == null || item.getType() != Material.TIPPED_ARROW || !(item.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }
        return meta.getBasePotionType() == PotionType.OOZING;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("spawnstash")) {
            return handleSpawnStash(sender, args);
        }
        return handleStash(sender, args);
    }

    private boolean handleSpawnStash(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cDieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("spawnstash.use")) {
            noPermission(player);
            return true;
        }
        if (args.length != 1) {
            send(player, "&eBenutzung: /spawnstash <name>");
            return true;
        }

        String name = cleanName(args[0]);
        File file = stashFile(name);
        if (!file.exists()) {
            send(player, "&cStash '&f" + name + "&c' wurde nicht gefunden.");
            return true;
        }

        pasteStash(player, file);
        return true;
    }

    private boolean handleStash(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cDieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("spawnstash.edit")) {
            noPermission(player);
            return true;
        }

        if (args.length == 0) {
            send(player, "&e/stash wand &7- Arrow of Oozing als Auswahlwerkzeug");
            send(player, "&e/stash save <name> &7- Auswahl speichern");
            send(player, "&e/stash list &7- gespeicherte Stashes");
            send(player, "&e/stash delete <name> &7- Stash löschen");
            send(player, "&e/stash clear &7- Auswahl löschen");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> giveWand(player);
            case "clear" -> {
                selections.remove(player.getUniqueId());
                send(player, "&aAuswahl gelöscht.");
            }
            case "list" -> listStashes(player);
            case "save" -> {
                if (args.length < 2) {
                    send(player, "&eBenutzung: /stash save <name>");
                    return true;
                }
                saveStash(player, cleanName(args[1]));
            }
            case "delete" -> {
                if (args.length < 2) {
                    send(player, "&eBenutzung: /stash delete <name>");
                    return true;
                }
                deleteStash(player, cleanName(args[1]));
            }
            case "reload" -> {
                reloadConfig();
                send(player, "&aSpawnStash neu geladen.");
            }
            default -> send(player, "&cUnbekannter Unterbefehl. Nutze /stash.");
        }
        return true;
    }

    private void giveWand(Player player) {
        ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
        PotionMeta meta = (PotionMeta) arrow.getItemMeta();
        meta.setBasePotionType(PotionType.OOZING);
        meta.setDisplayName(c("&aStash Selection Arrow"));
        meta.setLore(List.of(c("&7Rechtsklick: Ecke 1 / Ecke 2"), c("&7Danach: &e/stash save <name>")));
        arrow.setItemMeta(meta);
        player.getInventory().addItem(arrow);
        send(player, "&aDu hast einen Arrow of Oozing für die Auswahl bekommen.");
    }

    private void saveStash(Player player, String name) {
        if (name.isBlank()) {
            send(player, "&cUngültiger Name.");
            return;
        }

        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            send(player, "&cDu musst zuerst zwei Ecken mit einem Arrow of Oozing auswählen.");
            return;
        }
        if (!Objects.equals(selection.pos1.getWorld(), selection.pos2.getWorld())) {
            send(player, "&cDie Auswahl ist ungültig.");
            return;
        }

        long volume = volume(selection.pos1, selection.pos2);
        long max = Math.max(1, getConfig().getLong("settings.max-selection-volume", 50000));
        if (volume > max) {
            send(player, "&cDie Auswahl ist zu groß: &f" + volume + "&c/&f" + max + " &cBlöcke.");
            return;
        }

        World world = selection.pos1.getWorld();
        int minX = Math.min(selection.pos1.getBlockX(), selection.pos2.getBlockX());
        int minY = Math.min(selection.pos1.getBlockY(), selection.pos2.getBlockY());
        int minZ = Math.min(selection.pos1.getBlockZ(), selection.pos2.getBlockZ());
        int maxX = Math.max(selection.pos1.getBlockX(), selection.pos2.getBlockX());
        int maxY = Math.max(selection.pos1.getBlockY(), selection.pos2.getBlockY());
        int maxZ = Math.max(selection.pos1.getBlockZ(), selection.pos2.getBlockZ());

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("name", name);
        yml.set("size.x", maxX - minX + 1);
        yml.set("size.y", maxY - minY + 1);
        yml.set("size.z", maxZ - minZ + 1);

        int anchorX = player.getLocation().getBlockX() - minX;
        int anchorY = player.getLocation().getBlockY() - minY;
        int anchorZ = player.getLocation().getBlockZ() - minZ;
        if (anchorX < 0 || anchorX > maxX - minX || anchorY < 0 || anchorY > maxY - minY || anchorZ < 0 || anchorZ > maxZ - minZ) {
            anchorX = (maxX - minX) / 2;
            anchorY = 0;
            anchorZ = (maxZ - minZ) / 2;
        }
        yml.set("anchor.x", anchorX);
        yml.set("anchor.y", anchorY);
        yml.set("anchor.z", anchorZ);

        int id = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    String path = "blocks." + id++;
                    yml.set(path + ".x", x - minX);
                    yml.set(path + ".y", y - minY);
                    yml.set(path + ".z", z - minZ);
                    yml.set(path + ".data", block.getBlockData().getAsString());

                    Inventory inv = localInventory(block.getState());
                    if (inv != null) {
                        yml.set(path + ".inventory", Arrays.asList(inv.getContents()));
                    }
                }
            }
        }
        yml.set("block-count", id);

        try {
            yml.save(stashFile(name));
            send(player, "&aStash '&f" + name + "&a' gespeichert (&f" + volume + "&a Blöcke).");
            send(player, "&7Spawnen mit: &e/spawnstash " + name);
        } catch (IOException e) {
            getLogger().severe("Stash konnte nicht gespeichert werden: " + e.getMessage());
            send(player, "&cStash konnte nicht gespeichert werden. Siehe Konsole.");
        }
    }

    private void pasteStash(Player player, File file) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        int count = yml.getInt("block-count", 0);
        if (count <= 0) {
            send(player, "&cDiese Stash-Datei enthält keine Blöcke.");
            return;
        }

        int anchorX = yml.getInt("anchor.x", 0);
        int anchorY = yml.getInt("anchor.y", 0);
        int anchorZ = yml.getInt("anchor.z", 0);
        Location playerBlock = player.getLocation().getBlock().getLocation();
        World world = player.getWorld();
        int baseX = playerBlock.getBlockX() - anchorX;
        int baseY = playerBlock.getBlockY() - anchorY;
        int baseZ = playerBlock.getBlockZ() - anchorZ;

        // Pass 1: alle Blöcke setzen.
        for (int i = 0; i < count; i++) {
            String path = "blocks." + i;
            int x = baseX + yml.getInt(path + ".x");
            int y = baseY + yml.getInt(path + ".y");
            int z = baseZ + yml.getInt(path + ".z");
            String dataString = yml.getString(path + ".data", "minecraft:air");
            try {
                BlockData data = Bukkit.createBlockData(dataString);
                world.getBlockAt(x, y, z).setBlockData(data, false);
            } catch (IllegalArgumentException ex) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }

        // Pass 2: Container-Inhalte wiederherstellen.
        for (int i = 0; i < count; i++) {
            String path = "blocks." + i;
            if (!yml.isList(path + ".inventory")) continue;

            int x = baseX + yml.getInt(path + ".x");
            int y = baseY + yml.getInt(path + ".y");
            int z = baseZ + yml.getInt(path + ".z");
            Inventory inv = localInventory(world.getBlockAt(x, y, z).getState());
            if (inv == null) continue;

            List<?> saved = yml.getList(path + ".inventory", Collections.emptyList());
            ItemStack[] contents = new ItemStack[inv.getSize()];
            for (int slot = 0; slot < contents.length && slot < saved.size(); slot++) {
                Object obj = saved.get(slot);
                if (obj instanceof ItemStack item) contents[slot] = item;
            }
            inv.setContents(contents);
        }

        send(player, "&aStash '&f" + yml.getString("name", file.getName().replace(".yml", "")) + "&a' gespawnt.");
    }

    private Inventory localInventory(BlockState state) {
        if (state instanceof Chest chest) return chest.getBlockInventory();
        if (state instanceof Container container) return container.getInventory();
        return null;
    }

    private void deleteStash(Player player, String name) {
        File file = stashFile(name);
        if (!file.exists()) {
            send(player, "&cStash nicht gefunden.");
            return;
        }
        if (file.delete()) send(player, "&aStash '&f" + name + "&a' gelöscht.");
        else send(player, "&cStash konnte nicht gelöscht werden.");
    }

    private void listStashes(Player player) {
        String[] names = stashesFolder.list((dir, file) -> file.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (names == null || names.length == 0) {
            send(player, "&7Es sind noch keine Stashes gespeichert.");
            return;
        }
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        List<String> clean = Arrays.stream(names).map(n -> n.substring(0, n.length() - 4)).toList();
        send(player, "&eStashes: &f" + String.join("&7, &f", clean));
    }

    private File stashFile(String name) {
        return new File(stashesFolder, cleanName(name) + ".yml");
    }

    private String cleanName(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase(Locale.ROOT);
    }

    private long volume(Location a, Location b) {
        long x = Math.abs(a.getBlockX() - b.getBlockX()) + 1L;
        long y = Math.abs(a.getBlockY() - b.getBlockY()) + 1L;
        long z = Math.abs(a.getBlockZ() - b.getBlockZ()) + 1L;
        return x * y * z;
    }

    private String format(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void send(CommandSender sender, String text) {
        String prefix = getConfig().getString("messages.prefix", "&8[&cSpawnStash&8] &7");
        sender.sendMessage(c(prefix + text));
    }

    private void noPermission(CommandSender sender) {
        send(sender, getConfig().getString("messages.no-permission", "&cDafür hast du keine Rechte."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("spawnstash")) {
            if (args.length == 1) return filter(args[0], stashNames());
            return Collections.emptyList();
        }

        if (args.length == 1) return filter(args[0], List.of("wand", "save", "list", "delete", "clear", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return filter(args[1], stashNames());
        return Collections.emptyList();
    }

    private List<String> stashNames() {
        String[] files = stashesFolder.list((dir, name) -> name.endsWith(".yml"));
        if (files == null) return Collections.emptyList();
        return Arrays.stream(files).map(n -> n.substring(0, n.length() - 4)).sorted().toList();
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static final class Selection {
        private Location pos1;
        private Location pos2;
    }
}
