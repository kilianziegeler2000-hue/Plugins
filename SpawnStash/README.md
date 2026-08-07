# SpawnStash

Ein kleines Paper-Plugin für `/spawnstash`.

## Funktion
- `/spawnstash` baut direkt an der Position des Spielers eine kompakte Fake-Stash-Base.
- Die Base enthält Fake-Kisten/Barrels/Shulker mit zufälligem Loot, Crafting Table, Ender Chest und Anvil.
- Größe, Blöcke, Loot, Cooldown, Sounds und Partikel sind in `config.yml` einfach änderbar.
- `/spawnstash reload` lädt die Config neu.

## Rechte
- `spawnstash.use` – darf `/spawnstash` nutzen (standardmäßig OP)
- `spawnstash.reload` – darf `/spawnstash reload` nutzen (standardmäßig OP)

## Build
Benötigt Java 21 + Maven:

```bash
mvn clean package
```

Danach liegt die JAR unter `target/spawnstash-1.0.0.jar`.

Getestete Zielversion: Paper 1.21.4.
