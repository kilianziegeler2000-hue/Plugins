package de.openai.shopplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class EasyShopPlugin extends JavaPlugin implements Listener, TabExecutor {

    private Economy economy;
    private Object playerPointsApi;
    private Method playerPointsLook;
    private Method playerPointsTake;
    private String currencyProvider;
    private File shopFile;
    private YamlConfiguration shopConfig;
    private final DecimalFormat moneyFormat = new DecimalFormat("0.##");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("shop.yml", false);
        loadShop();

        if (!setupCurrency()) {
            getLogger().severe("Weder PlayerPoints noch eine Vault-Economy wurde gefunden. EasyShop wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EasyShop wurde aktiviert.");
    }

    private boolean setupCurrency() {
        String configured = getConfig().getString("settings.currency-provider", "auto").toLowerCase(Locale.ROOT);

        if ((configured.equals("auto") || configured.equals("playerpoints")) && setupPlayerPoints()) {
            currencyProvider = "playerpoints";
            getLogger().info("EasyShop verwendet PlayerPoints als Währung.");
            return true;
        }

        if ((configured.equals("auto") || configured.equals("vault")) && setupEconomy()) {
            currencyProvider = "vault";
            getLogger().info("EasyShop verwendet Vault als Währung.");
            return true;
        }

        return false;
    }

    private boolean setupPlayerPoints() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (plugin == null || !plugin.isEnabled()) return false;

            Method getApi = plugin.getClass().getMethod("getAPI");
            playerPointsApi = getApi.invoke(plugin);
            if (playerPointsApi == null) return false;

            playerPointsLook = playerPointsApi.getClass().getMethod("look", UUID.class);
            playerPointsTake = playerPointsApi.getClass().getMethod("take", UUID.class, int.class);
            return true;
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("PlayerPoints wurde gefunden, aber die API konnte nicht geladen werden: " + ex.getMessage());
            return false;
        }
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private String currencyName() {
        return currencyProvider != null && currencyProvider.equals("playerpoints")
                ? getConfig().getString("settings.playerpoints-name", "Points")
                : getConfig().getString("settings.vault-name", "$");
    }

    private boolean hasCurrency(Player player, double price) {
        if ("playerpoints".equals(currencyProvider)) {
            try {
                int required = (int) Math.ceil(price);
                Object value = playerPointsLook.invoke(playerPointsApi, player.getUniqueId());
                return value instanceof Number number && number.intValue() >= required;
            } catch (ReflectiveOperationException ex) {
                getLogger().warning("PlayerPoints-Guthaben konnte nicht gelesen werden: " + ex.getMessage());
                return false;
            }
        }
        return economy != null && economy.has(player, price);
    }

    private boolean withdrawCurrency(Player player, double price) {
        if ("playerpoints".equals(currencyProvider)) {
            try {
                int required = (int) Math.ceil(price);
                Object result = playerPointsTake.invoke(playerPointsApi, player.getUniqueId(), required);
                return !(result instanceof Boolean b) || b;
            } catch (ReflectiveOperationException ex) {
                getLogger().warning("PlayerPoints konnten nicht abgezogen werden: " + ex.getMessage());
                return false;
            }
        }
        if (economy == null) return false;
        var result = economy.withdrawPlayer(player, price);
        return result.transactionSuccess();
    }

    private void loadShop() {
        shopFile = new File(getDataFolder(), "shop.yml");
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private List<String> c(List<String> lines) {
        return lines.stream().map(this::c).collect(Collectors.toList());
    }

    private boolean isEditor(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission("easyshop.edit")) return true;
        List<String> editors = getConfig().getStringList("editors");
        String uuid = player.getUniqueId().toString();
        return editors.stream().anyMatch(e -> e.equalsIgnoreCase(player.getName()) || e.equalsIgnoreCase(uuid));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Dieser Befehl ist nur für Spieler.");
                return true;
            }
            openMain(player);
            return true;
        }

        if (!isEditor(sender)) {
            sender.sendMessage(c(getConfig().getString("settings.no-permission")));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadShop();
            sender.sendMessage(c(getConfig().getString("settings.reloaded")));
            return true;
        }

        if (args[0].equalsIgnoreCase("add") && args.length >= 3 && args[1].equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Dieser Befehl ist nur für Spieler.");
                return true;
            }

            double price;
            try {
                price = Double.parseDouble(args[2].replace(',', '.'));
            } catch (NumberFormatException ex) {
                player.sendMessage(c("&cBitte gib einen gültigen Preis an."));
                return true;
            }
            if (price < 0) {
                player.sendMessage(c("&cDer Preis darf nicht negativ sein."));
                return true;
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(c("&cDu musst ein Item in der Haupthand halten."));
                return true;
            }

            String category = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "food";
            ConfigurationSection cat = getConfig().getConfigurationSection("categories." + category);
            if (cat == null || cat.getBoolean("coming-soon", false)) {
                player.sendMessage(c("&cUnbekannte oder gesperrte Kategorie. Verfügbar: &f" + editableCategories()));
                return true;
            }

            addItem(category, hand.clone(), price);
            String message = getConfig().getString("settings.added", "&aItem hinzugefügt.")
                    .replace("%price%", moneyFormat.format(price))
                    .replace("%category%", category)
                    .replace("%currency%", currencyName());
            player.sendMessage(c(message));
            return true;
        }

        if (args[0].equalsIgnoreCase("editor") && args.length >= 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            String target = args[2];
            List<String> editors = new ArrayList<>(getConfig().getStringList("editors"));

            if (action.equals("add")) {
                if (editors.stream().noneMatch(e -> e.equalsIgnoreCase(target))) editors.add(target);
                getConfig().set("editors", editors);
                saveConfig();
                sender.sendMessage(c("&a" + target + " darf den Shop jetzt bearbeiten."));
                return true;
            }
            if (action.equals("remove")) {
                editors.removeIf(e -> e.equalsIgnoreCase(target));
                getConfig().set("editors", editors);
                saveConfig();
                sender.sendMessage(c("&e" + target + " wurde aus der Editor-Liste entfernt."));
                return true;
            }
        }

        sender.sendMessage(c("&e/shop &7- Shop öffnen"));
        sender.sendMessage(c("&e/shop add hand <preis> [kategorie] &7- Item aus der Hand hinzufügen"));
        sender.sendMessage(c("&e/shop editor <add|remove> <spieler> &7- Editor verwalten"));
        sender.sendMessage(c("&e/shop reload &7- Config neu laden"));
        return true;
    }

    private void addItem(String category, ItemStack item, double price) {
        List<Map<?, ?>> list = new ArrayList<>(shopConfig.getMapList("items." + category));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("price", price);
        entry.put("item", item);
        list.add(entry);
        shopConfig.set("items." + category, list);
        try {
            shopConfig.save(shopFile);
        } catch (IOException e) {
            getLogger().severe("shop.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private String editableCategories() {
        ConfigurationSection section = getConfig().getConfigurationSection("categories");
        if (section == null) return "";
        return section.getKeys(false).stream()
                .filter(k -> !getConfig().getBoolean("categories." + k + ".coming-soon", false))
                .collect(Collectors.joining(", "));
    }

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, c(getConfig().getString("settings.main-title", "&8Shop")));
        ConfigurationSection categories = getConfig().getConfigurationSection("categories");
        if (categories != null) {
            for (String key : categories.getKeys(false)) {
                String path = "categories." + key;
                int slot = getConfig().getInt(path + ".slot", 13);
                Material material = Material.matchMaterial(getConfig().getString(path + ".material", "STONE"));
                if (material == null) material = Material.STONE;
                String name = getConfig().getString(path + ".name", key);
                boolean comingSoon = getConfig().getBoolean(path + ".coming-soon", false);
                ItemStack icon = new ItemStack(material);
                ItemMeta meta = icon.getItemMeta();
                meta.setDisplayName(c(comingSoon ? getConfig().getString("settings.coming-soon-name", "&cComing Soon") : name));
                if (comingSoon) meta.setLore(c(getConfig().getStringList("settings.coming-soon-lore")));
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "category"), org.bukkit.persistence.PersistentDataType.STRING, key);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                icon.setItemMeta(meta);
                if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, icon);
            }
        }
        player.openInventory(inv);
    }

    private void openCategory(Player player, String category) {
        String title = c(getConfig().getString("categories." + category + ".name", category));
        Inventory inv = Bukkit.createInventory(null, 27, title);
        List<Map<?, ?>> list = shopConfig.getMapList("items." + category);
        int slot = 9;
        for (Map<?, ?> raw : list) {
            if (slot > 17) break;
            Object itemObj = raw.get("item");
            Object priceObj = raw.get("price");
            if (!(itemObj instanceof ItemStack item) || !(priceObj instanceof Number number)) continue;
            double price = number.doubleValue();
            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            List<String> extra = getConfig().getStringList("settings.buy-lore").stream()
                    .map(s -> s.replace("%price%", moneyFormat.format(price)).replace("%currency%", currencyName()))
                    .map(this::c)
                    .toList();
            lore.addAll(extra);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "shop-category"), org.bukkit.persistence.PersistentDataType.STRING, category);
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "shop-index"), org.bukkit.persistence.PersistentDataType.INTEGER, list.indexOf(raw));
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(c("&cZurück"));
        backMeta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "back"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        back.setItemMeta(backMeta);
        inv.setItem(22, back);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();

        var categoryKey = new org.bukkit.NamespacedKey(this, "category");
        var shopCategoryKey = new org.bukkit.NamespacedKey(this, "shop-category");
        var shopIndexKey = new org.bukkit.NamespacedKey(this, "shop-index");
        var backKey = new org.bukkit.NamespacedKey(this, "back");

        if (meta.getPersistentDataContainer().has(categoryKey, org.bukkit.persistence.PersistentDataType.STRING)) {
            event.setCancelled(true);
            String category = meta.getPersistentDataContainer().get(categoryKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (category == null || getConfig().getBoolean("categories." + category + ".coming-soon", false)) return;
            openCategory(player, category);
            return;
        }

        if (meta.getPersistentDataContainer().has(backKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
            event.setCancelled(true);
            openMain(player);
            return;
        }

        if (meta.getPersistentDataContainer().has(shopCategoryKey, org.bukkit.persistence.PersistentDataType.STRING)
                && meta.getPersistentDataContainer().has(shopIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
            event.setCancelled(true);
            String category = meta.getPersistentDataContainer().get(shopCategoryKey, org.bukkit.persistence.PersistentDataType.STRING);
            Integer index = meta.getPersistentDataContainer().get(shopIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (category == null || index == null) return;
            buy(player, category, index);
        }
    }

    private void buy(Player player, String category, int index) {
        List<Map<?, ?>> list = shopConfig.getMapList("items." + category);
        if (index < 0 || index >= list.size()) return;
        Map<?, ?> raw = list.get(index);
        if (!(raw.get("item") instanceof ItemStack item) || !(raw.get("price") instanceof Number number)) return;
        double price = number.doubleValue();

        if (!hasCurrency(player, price)) {
            player.sendMessage(c(getConfig().getString("settings.no-money")
                    .replace("%currency%", currencyName())));
            return;
        }
        if (player.getInventory().firstEmpty() == -1 && item.getAmount() > 0) {
            player.sendMessage(c(getConfig().getString("settings.inventory-full")));
            return;
        }

        if (!withdrawCurrency(player, price)) {
            player.sendMessage(c("&cKauf fehlgeschlagen. Die Währung konnte nicht abgezogen werden."));
            return;
        }
        player.getInventory().addItem(item.clone());
        String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(item.getItemMeta().getDisplayName())
                : item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String msg = getConfig().getString("settings.bought", "&aGekauft.")
                .replace("%item%", itemName)
                .replace("%price%", moneyFormat.format(price))
                .replace("%currency%", currencyName());
        player.sendMessage(c(msg));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(args[0], List.of("add", "reload", "editor"));
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) return filter(args[1], List.of("hand"));
        if (args.length == 2 && args[0].equalsIgnoreCase("editor")) return filter(args[1], List.of("add", "remove"));
        if (args.length == 4 && args[0].equalsIgnoreCase("add") && args[1].equalsIgnoreCase("hand")) {
            return filter(args[3], Arrays.asList(editableCategories().split(", ")));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("editor")) {
            return filter(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return Collections.emptyList();
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
