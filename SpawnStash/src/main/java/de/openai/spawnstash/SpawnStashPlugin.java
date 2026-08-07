package de.openai.spawnstash;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Barrel;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnStashPlugin extends JavaPlugin {

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("spawnstash")).setExecutor(this);
        getLogger().info("SpawnStash aktiviert.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("spawnstash.reload")) {
                msg(sender, "messages.no-permission");
                return true;
            }
            reloadConfig();
            msg(sender, "messages.reloaded");
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "messages.players-only");
            return true;
        }

        if (!player.hasPermission("spawnstash.use")) {
            msg(player, "messages.no-permission");
            return true;
        }

        int cooldown = Math.max(0, getConfig().getInt("settings.cooldown-seconds", 15));
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long left = cooldown * 1000L - (now - last);
        if (left > 0 && !player.hasPermission("spawnstash.reload")) {
            String raw = getConfig().getString("messages.cooldown", "&cWarte noch %seconds%s.");
            send(player, raw.replace("%seconds%", String.valueOf((left + 999) / 1000)));
            return true;
        }

        Location origin = player.getLocation().getBlock().getLocation();
        int height = Math.max(3, getConfig().getInt("base.height", 4));
        if (origin.getBlockY() < player.getWorld().getMinHeight() + 2 ||
                origin.getBlockY() + height + 2 >= player.getWorld().getMaxHeight()) {
            msg(player, "messages.unsafe-height");
            return true;
        }

        spawnBase(player, origin);
        cooldowns.put(player.getUniqueId(), now);
        msg(player, "messages.spawned");
        return true;
    }

    private void spawnBase(Player player, Location origin) {
        FileConfiguration c = getConfig();
        World world = origin.getWorld();

        int width = oddAtLeast(c.getInt("base.width", 7), 5);
        int depth = oddAtLeast(c.getInt("base.depth", 7), 5);
        int height = Math.max(3, c.getInt("base.height", 4));
        int hx = width / 2;
        int hz = depth / 2;

        Material floor = material("base.floor", Material.OBSIDIAN);
        Material walls = material("base.walls", Material.DEEPSLATE_BRICKS);
        Material roof = material("base.roof", Material.OBSIDIAN);
        Material corner = material("base.corner-block", Material.CRYING_OBSIDIAN);
        Material light = material("base.light-block", Material.SEA_LANTERN);

        int baseY = origin.getBlockY() - 1;
        int cx = origin.getBlockX();
        int cz = origin.getBlockZ();

        // Floor + room shell. The room is intentionally "fake" and compact.
        for (int x = -hx; x <= hx; x++) {
            for (int z = -hz; z <= hz; z++) {
                set(world, cx + x, baseY, cz + z, floor);

                for (int y = 1; y <= height; y++) {
                    boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
                    if (edge) {
                        boolean isCorner = Math.abs(x) == hx && Math.abs(z) == hz;
                        set(world, cx + x, baseY + y, cz + z, isCorner ? corner : walls);
                    } else {
                        set(world, cx + x, baseY + y, cz + z, Material.AIR);
                    }
                }
                set(world, cx + x, baseY + height + 1, cz + z, roof);
            }
        }

        // Lights in the roof.
        set(world, cx, baseY + height + 1, cz, light);
        if (hx >= 3 && hz >= 3) {
            set(world, cx - 2, baseY + height + 1, cz - 2, light);
            set(world, cx + 2, baseY + height + 1, cz + 2, light);
        }

        // Fake storage wall.
        Material chestMat = material("base.chest-material", Material.CHEST);
        Material barrelMat = material("base.barrel-material", Material.BARREL);
        Material shulkerMat = material("base.shulker-material", Material.PURPLE_SHULKER_BOX);

        placeContainer(world, cx - 2, baseY + 1, cz - hz + 1, chestMat);
        placeContainer(world, cx,     baseY + 1, cz - hz + 1, barrelMat);
        placeContainer(world, cx + 2, baseY + 1, cz - hz + 1, shulkerMat);

        placeContainer(world, cx - 2, baseY + 2, cz - hz + 1, shulkerMat);
        placeContainer(world, cx,     baseY + 2, cz - hz + 1, chestMat);
        placeContainer(world, cx + 2, baseY + 2, cz - hz + 1, barrelMat);

        // Utility blocks along opposite wall.
        int utilZ = cz + hz - 1;
        int slot = cx - 2;
        if (c.getBoolean("base.crafting-table", true)) set(world, slot++, baseY + 1, utilZ, Material.CRAFTING_TABLE);
        if (c.getBoolean("base.ender-chest", true)) set(world, slot++, baseY + 1, utilZ, Material.ENDER_CHEST);
        if (c.getBoolean("base.anvil", true)) set(world, slot, baseY + 1, utilZ, Material.ANVIL);

        // A couple of visual "stash" blocks.
        if (width >= 7 && depth >= 7) {
            set(world, cx - hx + 1, baseY + 1, cz, Material.OBSIDIAN);
            set(world, cx + hx - 1, baseY + 1, cz, Material.RESPAWN_ANCHOR);
        }

        if (c.getBoolean("settings.teleport-inside", true)) {
            Location inside = new Location(world, cx + 0.5, baseY + 1.0, cz + 0.5, player.getYaw(), player.getPitch());
            player.teleport(inside);
        }

        if (c.getBoolean("settings.play-sound", true)) {
            world.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 0.8f);
            world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.3f);
        }
        if (c.getBoolean("settings.particles", true)) {
            world.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 60, 1.7, 1.2, 1.7, 0.05);
        }
    }

    private void placeContainer(World world, int x, int y, int z, Material material) {
        if (!material.isBlock()) material = Material.CHEST;
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);

        Inventory inv = null;
        if (block.getState() instanceof Chest chest) inv = chest.getBlockInventory();
        else if (block.getState() instanceof Barrel barrel) inv = barrel.getInventory();
        else if (block.getState() instanceof ShulkerBox shulker) inv = shulker.getInventory();

        if (inv != null) fillLoot(inv);
    }

    private void fillLoot(Inventory inv) {
        List<String> loot = getConfig().getStringList("base.fake-loot");
        if (loot.isEmpty()) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        int entries = Math.min(inv.getSize(), r.nextInt(4, 9));
        for (int i = 0; i < entries; i++) {
            String line = loot.get(r.nextInt(loot.size()));
            ItemStack item = parseLoot(line, r);
            if (item == null) continue;

            int slot;
            int tries = 0;
            do {
                slot = r.nextInt(inv.getSize());
                tries++;
            } while (inv.getItem(slot) != null && tries < 20);

            if (inv.getItem(slot) == null) inv.setItem(slot, item);
        }
    }

    private ItemStack parseLoot(String line, ThreadLocalRandom r) {
        try {
            String[] p = line.split(":", 2);
            Material mat = Material.matchMaterial(p[0].trim());
            if (mat == null || !mat.isItem()) return null;
            int min = 1, max = 1;
            if (p.length == 2) {
                String[] range = p[1].split("-", 2);
                min = Integer.parseInt(range[0].trim());
                max = range.length == 2 ? Integer.parseInt(range[1].trim()) : min;
            }
            int amount = r.nextInt(Math.max(1, min), Math.max(min, max) + 1);
            amount = Math.min(amount, mat.getMaxStackSize());
            return new ItemStack(mat, amount);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int oddAtLeast(int value, int min) {
        value = Math.max(min, value);
        return value % 2 == 0 ? value + 1 : value;
    }

    private Material material(String path, Material fallback) {
        String name = getConfig().getString(path, fallback.name());
        Material mat = Material.matchMaterial(name == null ? fallback.name() : name);
        return mat != null && mat.isBlock() ? mat : fallback;
    }

    private void set(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private void msg(CommandSender sender, String path) {
        String text = getConfig().getString(path, "");
        send(sender, text);
    }

    private void send(CommandSender sender, String text) {
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + text));
    }
}
