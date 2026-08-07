# EasyShop 1.2.0

Neu:
- `/shop editor` öffnet einen Ingame-Editor.
- Im Editor kann zwischen Vault und PlayerPoints gewechselt werden.
- Kategorien können über den Editor umbenannt werden.
- Items können im Editor aus der Hand hinzugefügt werden.
- Item-Preise können im Editor geändert werden.
- Items können mit Shift + Rechtsklick gelöscht werden.
- Nur konfigurierte Editoren oder Spieler mit `easyshop.edit` dürfen bearbeiten.

Build: `mvn clean package`


## Pro-Item-Währung (v1.3.0)

Jedes Shop-Item kann jetzt seine eigene Währung haben.

- `/shop add hand 250 vault food` – Item kostet 250 Vault-Geld.
- `/shop add hand 250 playerpoints food` – Item kostet 250 PlayerPoints.
- Kurzformen `points`, `pp`, `money` und `geld` werden ebenfalls erkannt.
- Die alte Schreibweise `/shop add hand 250 food` funktioniert weiterhin und nutzt die Standard-Währung.
- Im `/shop editor` zeigt jedes Item seine Währung. Rechtsklick wechselt zwischen Vault und PlayerPoints, Linksklick ändert den Preis, Shift+Rechtsklick löscht.
- Beim Hinzufügen im Editor gibt es getrennte Buttons für Vault und PlayerPoints.
- Alte Shop-Einträge ohne `currency:` funktionieren weiter und verwenden die Standard-Währung.
