# EasyShop

Ein simples Paper-Shop-Plugin im Stil der bereitgestellten Screenshots.

## Voraussetzungen
- Paper 1.21.4 (Java 21)
- Vault
- Ein Economy-Plugin mit Vault-Support, z. B. EssentialsX Economy

## Befehle
- `/shop` – öffnet das Shop-Menü
- `/shop add hand <preis> [kategorie]` – speichert das Item aus deiner Haupthand inklusive Name, Lore, Enchants, CustomModelData usw.
- `/shop reload` – lädt `config.yml` und `shop.yml` neu
- `/shop editor add <spieler>` – fügt einen Shop-Editor hinzu
- `/shop editor remove <spieler>` – entfernt einen Shop-Editor

Kategorien: `stars`, `food`, `equipment`, `nether`.
Wenn keine Kategorie bei `/shop add hand` angegeben wird, wird `food` benutzt.

## Rechte / Editoren
Nur Spieler in `config.yml -> editors` oder mit `easyshop.edit` dürfen bearbeiten.
Normale Spieler brauchen nur `easyshop.use` (standardmäßig alle).

## Einfache Bearbeitung
- Menü-Kategorien, Icons, Namen, Slots und Texte: `config.yml`
- Shop-Items und Preise: `shop.yml`
- Am einfachsten: Item in die Hand nehmen und `/shop add hand 250 food` ausführen.

## Build
Im Projektordner:

```bash
mvn clean package
```

Danach liegt die JAR in `target/EasyShop-1.0.0.jar`.

## PlayerPoints-Unterstützung (v1.1.0)
EasyShop unterstützt jetzt **PlayerPoints direkt**. Standardmäßig steht in `config.yml` `currency-provider: auto`: Wenn PlayerPoints installiert ist, werden Käufe mit Points bezahlt. Falls PlayerPoints fehlt, wird automatisch Vault verwendet.

Mögliche Werte:
- `auto` - PlayerPoints bevorzugen, sonst Vault
- `playerpoints` - nur PlayerPoints verwenden
- `vault` - nur Vault verwenden

Für PlayerPoints sind Preise ganzzahlig sinnvoll. Falls ein Dezimalpreis eingetragen wird, rundet EasyShop beim Abbuchen auf den nächsten ganzen Point auf.
