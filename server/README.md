---
title: Radar
emoji: 📡
colorFrom: green
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
---

Radar-Server: weiß, was gerade auf tausenden Internetradios läuft.

- `/health` – Status
- `/search?q=...` – wo läuft das gerade?
- `/now` – alles, was gerade läuft

## Server teilen mit Valdrakor (das Spiel entscheidet)

Auf dem Oracle-Server läuft auch das Spiel Valdrakor. **Das Spiel entscheidet, wer wie viel Leistung bekommt.**

- Grenzen setzt das Spiel per systemd (`/etc/systemd/system/radar.service.d/60-sparsam.conf`, Lastwächter `valdrakor-last`).
  Hitradar ändert diese Dateien nie und setzt sie bei Updates nicht zurück.
- `radar.py` fragt alle 30 s `http://127.0.0.1:8100/api/last`. Bei `"hitradar": "low"` spart Hitradar selbst:
  nur ein Drittel der Abfragen, seltener nachschauen, keine KI-Anfragen, keine Cover-Suche. Danach von selbst wieder normal.
  Antwortet das Spiel nicht, läuft Hitradar normal weiter (innerhalb der gesetzten Grenzen).
- Hitradars eigene KI (`llm.service`) hat geringeren Rechenanteil als das Spiel.
- `/health` zeigt `spar` und `spiel_stufe`.

### Serverabsprache (ab 26.11.)

- Valdrakor schreibt alle 30 s `/run/serverabsprache/lage.json` mit `frei`, `sparsam` oder `pause` je Dienst.
- Hitradar liest den Eintrag `hitradar` (auch `radar`/`radar.service`, flach oder unter `dienste`/`services`):
  `frei` = normal, `sparsam` = ein Drittel der Abfragen, keine KI, keine Cover, `pause` = gar keine Abfragen
  (die App bekommt den letzten Stand). Datei fehlt oder ist älter als 2 Minuten: Rückfall auf `/api/last`, sonst `frei`.
- Anmeldung: `radar.py` schreibt beim Start einmal `/var/lib/serverabsprache/hitradar.json` (Dienste, Ports, Stufen).
- `/health` zeigt `modus` und `modus_quelle`.

### Prüfregel Geheimnisse

- Server-Skripte (`server/ops/*.sh`) und Workflows dürfen nichts ausgeben, was Passwörter, Tokens oder Schlüssel enthält:
  kein `set -x`, kein `env`/`printenv`, kein `systemctl cat`, keine `.env`-Dateien anzeigen, kein `docker inspect`.
- `.github/scripts/check-secrets-rule.sh` prüft das bei jedem Push (Workflow „Prüfregel Geheimnisse“) und vor jedem Server-Lauf.
- Alles, was vom Server ins Protokoll kommt, läuft zusätzlich durch `.github/scripts/redact.py` (macht daraus `***`).
