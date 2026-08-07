package de.openai.shopplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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

    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();

    private record PendingInput(InputType type, String category, int index, ItemStack item, String currency) {}
    private enum InputType { RENAME_CATEGORY, RENAME_ITEM, CHANGE_PRICE, ADD_HAND_PRICE }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("shop.yml", false);
        loadShop();

        if (!setupCurrencies()) {
            getLogger().severe("Weder PlayerPoints noch eine Vault-Economy wurde gefunden. EasyShop wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EasyShop 1.4.0 wurde aktiviert. Standard-Währung: " + currencyProvider);
    }

    private boolean setupCurrencies() {
        economy = null;
        playerPointsApi = null;
        playerPointsLook = null;
        playerPointsTake = null;

        boolean playerPointsAvailable = setupPlayerPoints();
        boolean vaultAvailable = setupEconomy();

        String configured = getConfig().getString("settings.currency-provider", "auto").toLowerCase(Locale.ROOT);
        if (configured.equals("playerpoints") && playerPointsAvailable) {
            currencyProvider = "playerpoints";
        } else if (configured.equals("vault") && vaultAvailable) {
            currencyProvider = "vault";
        } else if (playerPointsAvailable) {
            currencyProvider = "playerpoints";
        } else if (vaultAvailable) {
            currencyProvider = "vault";
        } else {
            return false;
        }

        return true;
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

    private boolean switchCurrency(Player player, String target) {
        target = normalizeCurrency(target);
        if (target == null) {
            player.sendMessage(c("&cUnbekannte Währung. Nutze vault oder playerpoints."));
            return false;
        }

        if (!isCurrencyAvailable(target)) {
            player.sendMessage(c(target.equals("playerpoints")
                    ? "&cPlayerPoints ist nicht installiert oder nicht aktiv."
                    : "&cVault oder ein Vault-Economy-Plugin ist nicht verfügbar."));
            return false;
        }

        currencyProvider = target;
        getConfig().set("settings.currency-provider", target);
        saveConfig();
        player.sendMessage(c("&aStandard-Währung wurde auf &f" + displayProvider(target) + " &agesetzt."));
        return true;
    }

    private String normalizeCurrency(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "vault", "money", "geld" -> "vault";
            case "playerpoints", "points", "point", "pp" -> "playerpoints";
            default -> null;
        };
    }

    private boolean isCurrencyAvailable(String provider) {
        return "playerpoints".equals(provider)
                ? playerPointsApi != null && playerPointsLook != null && playerPointsTake != null
                : "vault".equals(provider) && economy != null;
    }

    private String displayProvider(String provider) {
        return "playerpoints".equalsIgnoreCase(provider) ? "PlayerPoints" : "Vault";
    }

    private String currencyName() {
        return currencyName(currencyProvider);
    }

    private String currencyName(String provider) {
        return "playerpoints".equals(provider)
                ? getConfig().getString("settings.playerpoints-name", "Points")
                : getConfig().getString("settings.vault-name", "$");
    }

    private boolean hasCurrency(Player player, double price, String provider) {
        if ("playerpoints".equals(provider)) {
            if (!isCurrencyAvailable("playerpoints")) return false;
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

    private boolean withdrawCurrency(Player player, double price, String provider) {
        if ("playerpoints".equals(provider)) {
            if (!isCurrencyAvailable("playerpoints")) return false;
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
        return economy.withdrawPlayer(player, price).transactionSuccess();
    }

    private String currencyOf(Map<?, ?> raw) {
        Object value = raw.containsKey("currency") ? raw.get("currency") : currencyProvider;
        String configured = normalizeCurrency(String.valueOf(value));
        return configured != null ? configured : currencyProvider;
    }

    private void loadShop() {
        shopFile = new File(getDataFolder(), "shop.yml");
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    private void saveShop() {
        try {
            shopConfig.save(shopFile);
        } catch (IOException e) {
            getLogger().severe("shop.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
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
            sender.sendMessage(c(getConfig().getString("settings.no-permission", "&cKeine Berechtigung.")));
            return true;
        }

        if (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("editor")) {
            if (args.length == 1) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Dieser Befehl ist nur für Spieler.");
                    return true;
                }
                openEditorMain(player);
                return true;
            }

            if (args.length >= 3) {
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
        }

        if (args[0].equalsIgnoreCase("currency") && args.length >= 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Nutze den Ingame-Editor als Spieler.");
                return true;
            }
            if (switchCurrency(player, args[1])) openEditorMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadShop();
            if (!setupCurrencies()) {
                sender.sendMessage(c("&cConfig geladen, aber die eingestellte Währung ist nicht verfügbar."));
            } else {
                sender.sendMessage(c(getConfig().getString("settings.reloaded", "&aEasyShop wurde neu geladen.")));
            }
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

            String itemCurrency = currencyProvider;
            String category = "food";

            if (args.length >= 4) {
                String maybeCurrency = normalizeCurrency(args[3]);
                if (maybeCurrency != null) {
                    itemCurrency = maybeCurrency;
                    if (args.length >= 5) category = args[4].toLowerCase(Locale.ROOT);
                } else {
                    // Abwärtskompatibel: /shop add hand <preis> <kategorie>
                    category = args[3].toLowerCase(Locale.ROOT);
                }
            }

            if (!isCurrencyAvailable(itemCurrency)) {
                player.sendMessage(c("&c" + displayProvider(itemCurrency) + " ist auf diesem Server nicht verfügbar."));
                return true;
            }

            ConfigurationSection cat = getConfig().getConfigurationSection("categories." + category);
            if (cat == null || cat.getBoolean("coming-soon", false)) {
                player.sendMessage(c("&cUnbekannte oder gesperrte Kategorie. Verfügbar: &f" + editableCategories()));
                return true;
            }

            addItem(category, hand.clone(), price, itemCurrency);
            String message = getConfig().getString("settings.added", "&aItem hinzugefügt.")
                    .replace("%price%", moneyFormat.format(price))
                    .replace("%category%", category)
                    .replace("%currency%", currencyName(itemCurrency));
            player.sendMessage(c(message));
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(c("&e/shop &7- Shop öffnen"));
        sender.sendMessage(c("&e/shop edit &7- Ingame-Editor öffnen"));
        sender.sendMessage(c("&e/shop add hand <preis> [vault|playerpoints] [kategorie] &7- Item aus der Hand hinzufügen"));
        sender.sendMessage(c("&e/shop currency <vault|playerpoints> &7- Währung wechseln"));
        sender.sendMessage(c("&e/shop edit <add|remove> <spieler> &7- Editor verwalten"));
        sender.sendMessage(c("&e/shop reload &7- Config neu laden"));
    }

    private void addItem(String category, ItemStack item, double price, String itemCurrency) {
        List<Map<?, ?>> list = new ArrayList<>(shopConfig.getMapList("items." + category));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("price", price);
        entry.put("currency", itemCurrency);
        entry.put("item", item);
        list.add(entry);
        shopConfig.set("items." + category, list);
        saveShop();
    }

    private void updatePrice(String category, int index, double price) {
        List<Map<?, ?>> oldList = shopConfig.getMapList("items." + category);
        if (index < 0 || index >= oldList.size()) return;
        List<Map<String, Object>> newList = new ArrayList<>();
        for (int i = 0; i < oldList.size(); i++) {
            Map<?, ?> raw = oldList.get(i);
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (i == index) copy.put("price", price);
            newList.add(copy);
        }
        shopConfig.set("items." + category, newList);
        saveShop();
    }

    private void updateItemCurrency(String category, int index, String itemCurrency) {
        List<Map<?, ?>> oldList = shopConfig.getMapList("items." + category);
        if (index < 0 || index >= oldList.size()) return;
        List<Map<String, Object>> newList = new ArrayList<>();
        for (int i = 0; i < oldList.size(); i++) {
            Map<?, ?> raw = oldList.get(i);
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (i == index) copy.put("currency", itemCurrency);
            newList.add(copy);
        }
        shopConfig.set("items." + category, newList);
        saveShop();
    }

    private void updateItemName(String category, int index, String newName) {
        List<Map<?, ?>> oldList = shopConfig.getMapList("items." + category);
        if (index < 0 || index >= oldList.size()) return;
        List<Map<String, Object>> newList = new ArrayList<>();
        for (int i = 0; i < oldList.size(); i++) {
            Map<?, ?> raw = oldList.get(i);
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (i == index && copy.get("item") instanceof ItemStack item) {
                ItemStack renamed = item.clone();
                ItemMeta meta = renamed.getItemMeta();
                meta.setDisplayName(c(newName));
                renamed.setItemMeta(meta);
                copy.put("item", renamed);
            }
            newList.add(copy);
        }
        shopConfig.set("items." + category, newList);
        saveShop();
    }

    private void removeItem(String category, int index) {
        List<Map<?, ?>> list = new ArrayList<>(shopConfig.getMapList("items." + category));
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        shopConfig.set("items." + category, list);
        saveShop();
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
                meta.getPersistentDataContainer().set(key("category"), PersistentDataType.STRING, key);
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
        for (int i = 0; i < list.size() && slot <= 17; i++) {
            Map<?, ?> raw = list.get(i);
            Object itemObj = raw.get("item");
            Object priceObj = raw.get("price");
            if (!(itemObj instanceof ItemStack item) || !(priceObj instanceof Number number)) continue;
            double price = number.doubleValue();
            String itemCurrency = currencyOf(raw);
            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            List<String> extra = getConfig().getStringList("settings.buy-lore").stream()
                    .map(s -> s.replace("%price%", moneyFormat.format(price)).replace("%currency%", currencyName(itemCurrency)))
                    .map(this::c)
                    .toList();
            lore.addAll(extra);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(key("shop-category"), PersistentDataType.STRING, category);
            meta.getPersistentDataContainer().set(key("shop-index"), PersistentDataType.INTEGER, i);
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        inv.setItem(22, button(Material.RED_STAINED_GLASS_PANE, "&cZurück", "back", List.of()));
        player.openInventory(inv);
    }

    private void openEditorMain(Player player) {
        if (!isEditor(player)) return;
        Inventory inv = Bukkit.createInventory(null, 45, c("&8Shop Editor"));
        ConfigurationSection categories = getConfig().getConfigurationSection("categories");
        if (categories != null) {
            for (String key : categories.getKeys(false)) {
                String path = "categories." + key;
                int sourceSlot = getConfig().getInt(path + ".slot", 13);
                int slot = Math.min(26, Math.max(0, sourceSlot));
                Material material = Material.matchMaterial(getConfig().getString(path + ".material", "STONE"));
                if (material == null) material = Material.STONE;
                ItemStack icon = new ItemStack(material);
                ItemMeta meta = icon.getItemMeta();
                meta.setDisplayName(c(getConfig().getString(path + ".name", key)));
                meta.setLore(c(List.of(
                        "&7Interner Name: &f" + key,
                        "",
                        "&eKlicken zum Bearbeiten"
                )));
                meta.getPersistentDataContainer().set(key("editor-action"), PersistentDataType.STRING, "open-category");
                meta.getPersistentDataContainer().set(key("editor-category"), PersistentDataType.STRING, key);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                icon.setItemMeta(meta);
                inv.setItem(slot, icon);
            }
        }

        String next = "vault".equals(currencyProvider) ? "playerpoints" : "vault";
        ItemStack currency = button(Material.EMERALD,
                "&aWährung: &f" + displayProvider(currencyProvider),
                "switch-currency",
                List.of("&7Aktuell: &f" + displayProvider(currencyProvider), "", "&eKlicken → " + displayProvider(next)));
        currency.getItemMeta().getPersistentDataContainer().set(key("editor-target"), PersistentDataType.STRING, next);
        ItemMeta currencyMeta = currency.getItemMeta();
        currencyMeta.getPersistentDataContainer().set(key("editor-target"), PersistentDataType.STRING, next);
        currency.setItemMeta(currencyMeta);
        inv.setItem(40, currency);

        inv.setItem(44, button(Material.BARRIER, "&cSchließen", "close-editor", List.of()));
        player.openInventory(inv);
    }

    private void openCategoryEditor(Player player, String category) {
        if (!isEditor(player)) return;
        String displayName = getConfig().getString("categories." + category + ".name", category);
        Inventory inv = Bukkit.createInventory(null, 54, c("&8Editor: " + ChatColor.stripColor(c(displayName))));

        List<Map<?, ?>> list = shopConfig.getMapList("items." + category);
        for (int i = 0; i < list.size() && i < 45; i++) {
            Map<?, ?> raw = list.get(i);
            if (!(raw.get("item") instanceof ItemStack item) || !(raw.get("price") instanceof Number number)) continue;
            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            String itemCurrency = currencyOf(raw);
            String nextItemCurrency = "vault".equals(itemCurrency) ? "playerpoints" : "vault";
            lore.add(c("&7Preis: &e" + moneyFormat.format(number.doubleValue()) + " " + currencyName(itemCurrency)));
            lore.add(c("&7Währung: &f" + displayProvider(itemCurrency)));
            lore.add("");
            lore.add(c("&eLinksklick: Preis ändern"));
            lore.add(c("&dShift + Linksklick: Item umbenennen"));
            lore.add(c("&bRechtsklick: Währung → " + displayProvider(nextItemCurrency)));
            lore.add(c("&cShift + Rechtsklick: Löschen"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(key("editor-action"), PersistentDataType.STRING, "edit-item");
            meta.getPersistentDataContainer().set(key("editor-category"), PersistentDataType.STRING, category);
            meta.getPersistentDataContainer().set(key("editor-index"), PersistentDataType.INTEGER, i);
            display.setItemMeta(meta);
            inv.setItem(i, display);
        }

        ItemStack addVault = button(Material.GOLD_INGOT, "&aItem aus Hand hinzufügen &7(Vault)", "add-hand-gui",
                List.of("&7Halte das gewünschte Item", "&7in deiner Haupthand und klicke.", "", "&fBezahlung: &eVault"));
        ItemMeta addVaultMeta = addVault.getItemMeta();
        addVaultMeta.getPersistentDataContainer().set(key("editor-category"), PersistentDataType.STRING, category);
        addVaultMeta.getPersistentDataContainer().set(key("editor-currency"), PersistentDataType.STRING, "vault");
        addVault.setItemMeta(addVaultMeta);
        inv.setItem(45, addVault);

        ItemStack addPoints = button(Material.EMERALD, "&aItem aus Hand hinzufügen &7(PlayerPoints)", "add-hand-gui",
                List.of("&7Halte das gewünschte Item", "&7in deiner Haupthand und klicke.", "", "&fBezahlung: &bPlayerPoints"));
        ItemMeta addPointsMeta = addPoints.getItemMeta();
        addPointsMeta.getPersistentDataContainer().set(key("editor-category"), PersistentDataType.STRING, category);
        addPointsMeta.getPersistentDataContainer().set(key("editor-currency"), PersistentDataType.STRING, "playerpoints");
        addPoints.setItemMeta(addPointsMeta);
        inv.setItem(47, addPoints);

        ItemStack rename = button(Material.NAME_TAG, "&eKategorie umbenennen", "rename-category",
                List.of("&7Aktuell: &f" + displayName, "", "&eKlicken und Namen in den Chat schreiben"));
        ItemMeta renameMeta = rename.getItemMeta();
        renameMeta.getPersistentDataContainer().set(key("editor-category"), PersistentDataType.STRING, category);
        rename.setItemMeta(renameMeta);
        inv.setItem(46, rename);

        inv.setItem(49, button(Material.ARROW, "&cZurück", "editor-back", List.of()));
        player.openInventory(inv);
    }

    private ItemStack button(Material material, String name, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(c(name));
        if (!lore.isEmpty()) meta.setLore(c(lore));
        // Der normale Shop-"Zurück"-Button darf NICHT als Editor-Aktion behandelt werden.
        // Sonst wird er im Click-Handler vorher abgefangen und macht nichts.
        if (action.equals("back")) {
            meta.getPersistentDataContainer().set(key("back"), PersistentDataType.BYTE, (byte) 1);
        } else {
            meta.getPersistentDataContainer().set(key("editor-action"), PersistentDataType.STRING, action);
        }
        item.setItemMeta(meta);
        return item;
    }

    private org.bukkit.NamespacedKey key(String name) {
        return new org.bukkit.NamespacedKey(this, name);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();

        String editorAction = meta.getPersistentDataContainer().get(key("editor-action"), PersistentDataType.STRING);
        if (editorAction != null) {
            event.setCancelled(true);
            if (!isEditor(player)) {
                player.closeInventory();
                player.sendMessage(c(getConfig().getString("settings.no-permission", "&cKeine Berechtigung.")));
                return;
            }
            handleEditorClick(player, clicked, editorAction, event.getClick());
            return;
        }

        String category = meta.getPersistentDataContainer().get(key("category"), PersistentDataType.STRING);
        if (category != null) {
            event.setCancelled(true);
            if (getConfig().getBoolean("categories." + category + ".coming-soon", false)) return;
            openCategory(player, category);
            return;
        }

        if (meta.getPersistentDataContainer().has(key("back"), PersistentDataType.BYTE)) {
            event.setCancelled(true);
            openMain(player);
            return;
        }

        String shopCategory = meta.getPersistentDataContainer().get(key("shop-category"), PersistentDataType.STRING);
        Integer index = meta.getPersistentDataContainer().get(key("shop-index"), PersistentDataType.INTEGER);
        if (shopCategory != null && index != null) {
            event.setCancelled(true);
            buy(player, shopCategory, index);
        }
    }

    private void handleEditorClick(Player player, ItemStack clicked, String action, ClickType click) {
        ItemMeta meta = clicked.getItemMeta();
        String category = meta.getPersistentDataContainer().get(key("editor-category"), PersistentDataType.STRING);
        Integer index = meta.getPersistentDataContainer().get(key("editor-index"), PersistentDataType.INTEGER);

        switch (action) {
            case "open-category" -> {
                if (category != null) openCategoryEditor(player, category);
            }
            case "switch-currency" -> {
                String target = meta.getPersistentDataContainer().get(key("editor-target"), PersistentDataType.STRING);
                if (target != null && switchCurrency(player, target)) openEditorMain(player);
            }
            case "close-editor" -> player.closeInventory();
            case "editor-back" -> openEditorMain(player);
            case "rename-category" -> {
                if (category == null) return;
                pendingInputs.put(player.getUniqueId(), new PendingInput(InputType.RENAME_CATEGORY, category, -1, null, null));
                player.closeInventory();
                player.sendMessage(c("&eSchreibe jetzt den neuen Kategorienamen in den Chat."));
                player.sendMessage(c("&7Farbcodes mit & sind erlaubt. Schreibe &ccancel &7zum Abbrechen."));
            }
            case "add-hand-gui" -> {
                if (category == null) return;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    player.sendMessage(c("&cHalte zuerst ein Item in der Haupthand."));
                    return;
                }
                String itemCurrency = meta.getPersistentDataContainer().get(key("editor-currency"), PersistentDataType.STRING);
                if (itemCurrency == null) itemCurrency = currencyProvider;
                if (!isCurrencyAvailable(itemCurrency)) {
                    player.sendMessage(c("&c" + displayProvider(itemCurrency) + " ist auf diesem Server nicht verfügbar."));
                    return;
                }
                pendingInputs.put(player.getUniqueId(), new PendingInput(InputType.ADD_HAND_PRICE, category, -1, hand.clone(), itemCurrency));
                player.closeInventory();
                player.sendMessage(c("&eSchreibe jetzt den Preis in den Chat, z. B. &f250&e. &7Währung: &f" + displayProvider(itemCurrency)));
                player.sendMessage(c("&7Schreibe &ccancel &7zum Abbrechen."));
            }
            case "edit-item" -> {
                if (category == null || index == null) return;
                if (click == ClickType.SHIFT_RIGHT) {
                    removeItem(category, index);
                    player.sendMessage(c("&cItem wurde aus dem Shop gelöscht."));
                    openCategoryEditor(player, category);
                } else if (click == ClickType.SHIFT_LEFT) {
                    pendingInputs.put(player.getUniqueId(), new PendingInput(InputType.RENAME_ITEM, category, index, null, null));
                    player.closeInventory();
                    player.sendMessage(c("&eSchreibe jetzt den neuen Item-Namen in den Chat."));
                    player.sendMessage(c("&7Farbcodes mit & sind erlaubt. Schreibe &ccancel &7zum Abbrechen."));
                } else if (click.isRightClick()) {
                    List<Map<?, ?>> items = shopConfig.getMapList("items." + category);
                    if (index < 0 || index >= items.size()) return;
                    String current = currencyOf(items.get(index));
                    String next = "vault".equals(current) ? "playerpoints" : "vault";
                    if (!isCurrencyAvailable(next)) {
                        player.sendMessage(c("&c" + displayProvider(next) + " ist auf diesem Server nicht verfügbar."));
                        return;
                    }
                    updateItemCurrency(category, index, next);
                    player.sendMessage(c("&aItem-Währung wurde auf &f" + displayProvider(next) + " &agesetzt."));
                    openCategoryEditor(player, category);
                } else if (click.isLeftClick()) {
                    pendingInputs.put(player.getUniqueId(), new PendingInput(InputType.CHANGE_PRICE, category, index, null, null));
                    player.closeInventory();
                    player.sendMessage(c("&eSchreibe jetzt den neuen Preis in den Chat."));
                    player.sendMessage(c("&7Schreibe &ccancel &7zum Abbrechen."));
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(c("&eBearbeitung abgebrochen."));
                openCategoryEditor(player, pending.category());
                return;
            }

            switch (pending.type()) {
                case RENAME_CATEGORY -> {
                    if (message.length() > 48) {
                        player.sendMessage(c("&cDer Name ist zu lang. Maximal 48 Zeichen."));
                        openCategoryEditor(player, pending.category());
                        return;
                    }
                    getConfig().set("categories." + pending.category() + ".name", message);
                    saveConfig();
                    player.sendMessage(c("&aKategorie wurde zu &f" + message + " &aumbenannt."));
                    openCategoryEditor(player, pending.category());
                }
                case RENAME_ITEM -> {
                    if (message.length() > 64) {
                        player.sendMessage(c("&cDer Item-Name ist zu lang. Maximal 64 Zeichen."));
                        openCategoryEditor(player, pending.category());
                        return;
                    }
                    updateItemName(pending.category(), pending.index(), message);
                    player.sendMessage(c("&aItem wurde zu &f" + message + " &aumbenannt."));
                    openCategoryEditor(player, pending.category());
                }
                case CHANGE_PRICE -> {
                    Double price = parsePrice(message);
                    if (price == null || price < 0) {
                        player.sendMessage(c("&cUngültiger Preis."));
                        openCategoryEditor(player, pending.category());
                        return;
                    }
                    updatePrice(pending.category(), pending.index(), price);
                    player.sendMessage(c("&aPreis wurde geändert."));
                    openCategoryEditor(player, pending.category());
                }
                case ADD_HAND_PRICE -> {
                    Double price = parsePrice(message);
                    if (price == null || price < 0) {
                        player.sendMessage(c("&cUngültiger Preis."));
                        openCategoryEditor(player, pending.category());
                        return;
                    }
                    addItem(pending.category(), pending.item(), price, pending.currency());
                    player.sendMessage(c("&aItem wurde für &e" + moneyFormat.format(price) + " " + currencyName(pending.currency()) + " &ahinzugefügt."));
                    openCategoryEditor(player, pending.category());
                }
            }
        });
    }

    private Double parsePrice(String input) {
        try {
            return Double.parseDouble(input.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void buy(Player player, String category, int index) {
        List<Map<?, ?>> list = shopConfig.getMapList("items." + category);
        if (index < 0 || index >= list.size()) return;
        Map<?, ?> raw = list.get(index);
        if (!(raw.get("item") instanceof ItemStack item) || !(raw.get("price") instanceof Number number)) return;
        double price = number.doubleValue();
        String itemCurrency = currencyOf(raw);

        if (!isCurrencyAvailable(itemCurrency)) {
            player.sendMessage(c("&cDie Währung für dieses Item ist momentan nicht verfügbar."));
            return;
        }

        if (!hasCurrency(player, price, itemCurrency)) {
            player.sendMessage(c(getConfig().getString("settings.no-money", "&cNicht genug Geld.")
                    .replace("%currency%", currencyName(itemCurrency))));
            return;
        }
        if (player.getInventory().firstEmpty() == -1 && item.getAmount() > 0) {
            player.sendMessage(c(getConfig().getString("settings.inventory-full", "&cDein Inventar ist voll.")));
            return;
        }

        if (!withdrawCurrency(player, price, itemCurrency)) {
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
                .replace("%currency%", currencyName(itemCurrency));
        player.sendMessage(c(msg));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(args[0], List.of("edit", "editor", "add", "currency", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) return filter(args[1], List.of("hand"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("editor"))) return filter(args[1], List.of("add", "remove"));
        if (args.length == 2 && args[0].equalsIgnoreCase("currency")) return filter(args[1], List.of("vault", "playerpoints"));
        if (args.length == 4 && args[0].equalsIgnoreCase("add") && args[1].equalsIgnoreCase("hand")) {
            List<String> choices = new ArrayList<>(List.of("vault", "playerpoints"));
            choices.addAll(Arrays.asList(editableCategories().split(", ")));
            return filter(args[3], choices);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("add") && args[1].equalsIgnoreCase("hand")
                && normalizeCurrency(args[3]) != null) {
            return filter(args[4], Arrays.asList(editableCategories().split(", ")));
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("editor"))) {
            return filter(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return Collections.emptyList();
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
