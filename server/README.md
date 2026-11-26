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

### Serverabsprache (Stand 26.11., Details: docs/SERVER-ABSPRACHE.md im Valdrakor-Repo)

- Vorrang: Spiel vor Broker vor Hitradar vor Neuem.
- Stufe: `budget.hitradar.level` aus `/api/last` (Port 8100); ohne Netz aus `/run/serverabsprache/lage.json` (gleiches Feld).
  Datei fehlt oder älter als 2 Minuten: `sparsam`. Nach 15 Minuten ohne Datei selbst messen
  (Last je Kern ab 1,5 oder freier Speicher unter 10 % = `pause`, ab 1,0 oder unter 20 % = `sparsam`, sonst `frei`).
- `frei` = normal; `sparsam` = nichts Schweres neu (kein Neuladen der Senderliste), ein Drittel der Abfragen, keine KI, keine Cover;
  `pause` = radar und KI fangen nichts Neues an (App bekommt den letzten Stand).
- Meldung beim Start, täglich und vor/nach schwerer Arbeit: `/var/lib/serverabsprache/dienste/hitradar.json`
  `{"prio":"hitradar","was":"Radio und KI","jetzt":"leicht|schwer","bis":<ms, max 6 h>,"cpu":50,"ramMb":<echt gemessen>,"t":<ms>}`.
- Bei Speichermangel darf der Hausmeister des Spiels Hitradar zuerst anhalten und danach wieder starten.
- Umzug: auf dem alten Server `hitradar.json` löschen, auf dem neuen meldet sich radar.py beim Start selbst an.
- `/health` zeigt `modus`, `modus_quelle` und `meldung`.

### Prüfregel Geheimnisse

- Server-Skripte (`server/ops/*.sh`) und Workflows dürfen nichts ausgeben, was Passwörter, Tokens oder Schlüssel enthält:
  kein `set -x`, kein `env`/`printenv`, kein `systemctl cat`, keine `.env`-Dateien anzeigen, kein `docker inspect`.
- `.github/scripts/check-secrets-rule.sh` prüft das bei jedem Push (Workflow „Prüfregel Geheimnisse“) und vor jedem Server-Lauf.
- Alles, was vom Server ins Protokoll kommt, läuft zusätzlich durch `.github/scripts/redact.py` (macht daraus `***`).
