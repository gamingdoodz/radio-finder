# Hitradar – Projektwissen

Stand: 26.11.2026. Diese Datei ist die gemeinsame Grundlage für alle Chats im Projekt „Hitradar“.
Wer etwas Wichtiges ändert, trägt es hier ein (Abschnitt „Verlauf“).

## Regeln (immer)

- Antworten auf Deutsch, kurz und knackig. Kevin ist Elektriker, hat keinen PC, arbeitet nur mit dem Handy (Galaxy S25 Ultra).
- Commits immer als `user.name "gamingdoodz"`, `user.email "gamingdoodz@users.noreply.github.com"`.
- Nirgends erwähnen, womit die App gebaut wurde (nicht in App, Code oder Commit-Texten). Entwickler ist „gamingdoodz“.
- Alles muss kostenlos bleiben, keine laufenden Kosten, nichts, was automatisch bezahlt.
- Keine Geheimnisse ausgeben (Passwörter, Tokens, Schlüssel), auch nicht in Actions-Protokollen. Das Repo ist **öffentlich**.
- Pro Tag höchstens so oft in den Play-Test hochladen wie nötig (Google hat ein Tageslimit).

## Repo und Bauen

- GitHub: `gamingdoodz/radio-finder` (öffentlich). Paket `de.gamingdoodz.hitradar`, Code-Namespace `de.gamingdoodz.radiofinder`.
- App-Code: `app/src/main/java/de/gamingdoodz/radiofinder/` (Kotlin, Media3).
- Workflow **„App bauen“** (`.github/workflows/build.yml`): baut bei jedem Push; lädt in den internen + geschlossenen Play-Test hoch
  bei `gh workflow run "App bauen" --ref main`, täglich morgens per Zeitplan oder mit `[play]` in der Commit-Nachricht.
  Versionsnummer = Lauf-Nummer (`1.0.<run_number>`).
- Kein Android-SDK in der Cloud-Umgebung: Kompilieren nur über den Workflow.

## Wichtige Dateien der App

- `PlaybackService.kt` – Hintergrund-Wiedergabe, Android Auto (Listen, Suche, Sprachbefehle), Casten, Schlaf-Timer, Wecker-Einblenden,
  Werbung stumm, Weiterspielen nach dem letzten Lied. Android Auto darf nie „Auswahl konnte nicht geladen werden“ zeigen:
  `onSetMediaItems`/`onAddMediaItems` nie leer, Fehler abfangen, notfalls ein Sender mit Musik.
  Einträge: `news:` (Nachrichten), `hitmix:` (Hit-Mix-Kacheln), `hitmix:pl:` / `hitmix:my:` (Playlists), `st:` Sender, `jm:`/`au:` freie Lieder,
  `pc:`/`ia:`/`lv:` Hörspiele/Hörbücher.
- `HitMix.kt` – Hit-Mix: springt zu Sendern, auf denen gerade ein passender Hit anfängt. Lied-Ende aus dem Stream-Titel,
  Sprungbremse (max. 3 automatische Sprünge/Minute, nicht in den ersten 20 s), Stopp bleibt Stopp (nie von selbst wieder anfangen),
  Playlists zuerst mit eigenen Liedern, dann Genre. Wiedergabeliste „Als Nächstes“. Auswahl auf `Dispatchers.Default`, Übernahme auf Main.
- `MainActivity.kt` – Oberfläche. Tipps vor der Player-Verbindung werden nachgeholt (`pendingAction`).
- `LiveIndex.kt`, `Radar.kt` – was gerade wo läuft (Abgleich mit dem Server, Zwischenspeicher im Hintergrund neu berechnet).
- `WatchService.kt` – Wächter für Wunschlieder (Vordergrund-Dienst dataSync, Android-15-Zeitlimit beachtet).
- `Alarm.kt` – Radio-Wecker.

## Server

- Oracle Cloud Always Free, Frankfurt, `130.61.78.232` (2 Kerne, ~12 GB RAM, Speicher knapp).
- `server/radar.py` als `radar.service` (Port 8081) hinter Caddy: `https://130-61-78-232.sslip.io:8080` und 443. Eigene KI: `llm.service` (Port 8090).
- Push auf `server/**` startet **„Server aktualisieren“**. Ändert sich `server/ops/run.sh`, wird es auf dem Server ausgeführt
  (Ausgabe: `gh run view <id> --log`). Die Ausgabe läuft durch `.github/scripts/redact.py`.
- **Prüfregel Geheimnisse** (`.github/scripts/check-secrets-rule.sh`, Workflow „Prüfregel Geheimnisse“): in Server-Skripten und Workflows verboten:
  `set -x`, `env`/`printenv`, `systemctl cat`, `.env` anzeigen, `docker inspect`, Secrets per `echo`.

## Server teilen mit Valdrakor (das Spiel entscheidet)

- Auf demselben Server läuft Kevins Spiel **Valdrakor** (`/opt/valdrakor`, Docker, Port 8100). **Das Spiel entscheidet, wer wie viel Leistung bekommt.**
- Das Spiel drosselt Hitradar per systemd (`/etc/systemd/system/radar.service.d/60-sparsam.conf`: 20 % CPU, 6.000 Sender, 50 gleichzeitig)
  und per Lastwächter (`valdrakor-last`). Diese Dateien fasst Hitradar nie an.
- Serverabsprache: Valdrakor schreibt alle 30 s `/run/serverabsprache/lage.json` (`frei` / `sparsam` / `pause` je Dienst).
  `radar.py` liest das (Rückfall: `/api/last`, sonst `frei`) und hat sich in `/var/lib/serverabsprache/hitradar.json` angemeldet.
  `/health` zeigt `modus` und `modus_quelle`.

## Offen

- Android Auto, Hit-Mix, Playlists, Nachrichten im Auto testen (nur gebaut, nicht im Auto geprüft).
- Suchergebnisse in Android Auto werden nie geleert (wachsen langsam).
- `lage.json` gab es am 26.11. noch nicht: prüfen, ob Hitradar den Modus richtig liest, sobald Valdrakor sie schreibt.
- Noch keine 12 Tester für den geschlossenen Test.

## Verlauf

- 24.11.: Android Auto abgesichert, Hit-Mix wartet richtig, Stopp bleibt Stopp (1.0.126).
- 24.11.: Weiterspielen nach dem letzten Lied, Tippen beim Start, Wächter, Wecker, Suche ohne Ruckeln (1.0.128).
- 26.11.: Server richtet sich nach Valdrakor, Senderzahl nach Vorgabe, Serverabsprache, Prüfregel Geheimnisse.
