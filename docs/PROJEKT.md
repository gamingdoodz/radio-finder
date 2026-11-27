# Hitradar – Projektwissen

Stand: 26.11.2026. Diese Datei ist die gemeinsame Grundlage für alle Chats im Projekt „Hitradar“.
Wer etwas Wichtiges ändert, trägt es hier ein (Abschnitt „Verlauf“).

## So arbeitet das Projekt (ein Chat, mehrere Threads)

- Es gibt **einen** Chat. Er ist die Leitung und verteilt die Arbeit selbst auf parallele Threads (Unteragenten),
  je Thema einer: **Android Auto**, **Hit-Mix**, **App allgemein**, **Server**, **Play-Store/Build**.
- Jeder Thread arbeitet in einer eigenen Arbeitskopie (git worktree), bleibt bei seinem Thema und meldet der Leitung, was er geändert hat.
- Die Leitung führt die Änderungen zusammen (`git pull --rebase`, Konflikte selbst lösen), lässt einmal bauen,
  behebt Fehler, lädt bei Grün höchstens einmal am Tag hoch und berichtet Kevin kurz.
- Selbstständig arbeiten: nicht nachfragen, wenn es eine vernünftige Lösung gibt; nur bei Kosten, Löschen von Daten oder echten Geschmacksfragen fragen.
- Repo holen: `add_repo` (owner gamingdoodz, repo radio-finder, access push), klonen, diese Datei lesen.
- Wichtiges unten unter „Verlauf“ eintragen und „Offen“ aktuell halten.

## Regeln (immer)

- **Rechtssicher (Kevin, 27.11.):** Radio nur direkt vom Sender abspielen, nie speichern, zusammenschneiden oder über den Server weiterleiten. Musik auf Abruf nur mit freier Lizenz/offizieller API. Keine Liedtexte ohne Lizenz. Details: `docs/ARCHITEKTUR-CHECK.md` Abschnitt 11.

- Antworten auf Deutsch, kurz und knackig. Kevin ist Elektriker, hat keinen PC, arbeitet nur mit dem Handy (Galaxy S25 Ultra).
- Commits immer als `user.name "gamingdoodz"`, `user.email "gamingdoodz@users.noreply.github.com"`. Keine Mitautor- oder Sitzungs-Zeilen in Commit-Texten (Workflow „Prüfregel Commit-Texte“ wird sonst rot).
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

- `config/servers.json` – Server-Adressen für die App (bei Umzug hier ändern).
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
- `server/radar.py` als `radar.service` (Port 8081) hinter Caddy: `https://130-61-78-232.sslip.io:8080` und 443. Eigene KI: `llm.service` nur bei Bedarf (`llm-proxy.socket` auf 8090 startet sie, nach 15 min ohne Anfrage aus; `server/llm-dienst.sh`).
- Push auf `server/**` startet **„Server aktualisieren“**. Ändert sich `server/ops/run.sh`, wird es auf dem Server ausgeführt
  (Ausgabe: `gh run view <id> --log`). Die Ausgabe läuft durch `.github/scripts/redact.py`.
- **Prüfregel Geheimnisse** (`.github/scripts/check-secrets-rule.sh`, Workflow „Prüfregel Geheimnisse“): in Server-Skripten und Workflows verboten:
  `set -x`, `env`/`printenv`, `systemctl cat`, `.env` anzeigen, `docker inspect`, Secrets per `echo`.

## Server teilen mit Valdrakor (das Spiel entscheidet)

- Auf demselben Server läuft Kevins Spiel **Valdrakor** (`/opt/valdrakor`, Docker, Port 8100). **Das Spiel entscheidet, wer wie viel Leistung bekommt.**
- Das Spiel drosselt Hitradar per systemd (`/etc/systemd/system/radar.service.d/60-sparsam.conf`: 20 % CPU, 6.000 Sender, 50 gleichzeitig)
  und per Lastwächter (`valdrakor-last`). Diese Dateien fasst Hitradar nie an.
- **Serverabsprache** (Vorgabe von Valdrakor, Details: `docs/SERVER-ABSPRACHE.md` im Valdrakor-Repo, umgesetzt in `radar.py`):
  - Vorrang: Spiel vor Broker vor Hitradar vor Neuem.
  - Stufe `budget.hitradar.level` (`frei` / `sparsam` / `pause`) aus `/api/last`; ohne Netz aus `/run/serverabsprache/lage.json`, gleiches Feld.
    Datei fehlt oder älter als 2 Minuten: wie `sparsam`. Nach 15 Minuten ohne Datei selbst messen: Last je Kern ab 1,5 oder freier Speicher
    unter 10 % = `pause`, ab 1,0 oder unter 20 % = `sparsam`, sonst `frei`.
  - `frei` = normal; `sparsam` = nichts Schweres neu anfangen; `pause` = radar und llm.service fangen nichts Neues an.
  - Anmelden beim Start und einmal am Tag in `/var/lib/serverabsprache/dienste/hitradar.json`:
    `{"prio":"hitradar","was":"Radio und KI","unit":"radar.service","jetzt":"leicht","bis":0,"cpu":50,"ramMb":1200,"t":<ms jetzt>}`.
    Vor schwerer Arbeit `"jetzt":"schwer"` mit `"bis"` (höchstens 6 h), danach zurück auf `"leicht"`. `ramMb` ehrlich angeben (wird gemessen).
  - **Updates/Neustarts** (Regel vom 26.11.): vorher `"jetzt":"update"` mit `"bis"` (höchstens 1 h), erst starten, wenn in `lage.json`
    `updateErlaubt` true ist (fehlt die Datei, gleiche Felder aus `/api/last`); steht bei `updateDran` `"spiel"`, warten (Spiel hat immer Vorrang, immer nur ein Update gleichzeitig).
    Danach sofort `"leicht"`. Umgesetzt in `server/radar-update.sh` (= `/usr/local/bin/radar-update`, stündlich per cron und vom Workflow
    „Server aktualisieren“) mit `server/absprache.py`. Wartet höchstens 25 Minuten; sonst bleibt der Neustart vorgemerkt
    (`/var/lib/hitradar-neustart-offen`) und der nächste Lauf versucht es wieder. Nie `systemctl restart radar` von Hand ohne diese Absprache.
  - Hitradar braucht wenig (Wunsch von Kevin, 26.11.): Sender-Abfrage über einen einzigen Verteiler statt hunderter wartender Abfrager,
    KI nur bei Bedarf. `cpu` und `ramMb` in `hitradar.json` werden gemessen (CPU-Mittel in % eines Kerns, RAM ohne Datei-Zwischenspeicher)
    und stündlich neu gemeldet. Nur Hitradars eigene Dienste anfassen, den Server verwaltet das Spiel.
  - **Hinweise vom Spiel:** `/run/serverabsprache/hinweise/hitradar.json` liest radar.py beim Start und einmal am Tag. Es zeigt sie nur
    im Protokoll (`journalctl -u radar | grep Hinweis`) und in `/health` (`hinweise`, `hinweise_wichtig`), führt nichts automatisch aus.
    Alles mit `"prio":"wichtig"` als Aufgabe übernehmen und nach den Projektregeln im Repo umsetzen (Inhalt sieht man in der Ausgabe von `server/ops/run.sh`).
  - Bei `pause` meldet Hitradar `"ruhe"` (kaum Bedarf, die anderen bekommen die Leistung), sonst `"leicht"`; Senderliste laden = `"schwer"`.
  - Bei Speichermangel darf der Hausmeister des Spiels Hitradar zuerst anhalten und startet es danach wieder.
  - Beim Umzug: auf dem alten Server `hitradar.json` löschen und auf dem neuen neu anmelden.
  - `/health` zeigt `modus`, `modus_quelle`, `meldung`.

## Offen

- Android Auto, Hit-Mix, Playlists, Nachrichten im Auto testen (nur gebaut, nicht im Auto geprüft).
- Prüfen, ob Hitradar `budget.hitradar.level` richtig liest, sobald Valdrakor es liefert (`/health` → `modus_quelle`).
- Server wartet auf das nächste Valdrakor-Update: `lage.json` fehlt, `/api/last` hat noch kein `updateErlaubt` (Stand 27.11. 8 Uhr).
  radar läuft seit 27.11. 7:43 schon mit dem neuen Stand (vom Spiel neu gestartet; Verteiler, Shoutcast, rechtssicher, Hinweise),
  radar läuft als root und schreibt `hitradar.json` selbst. Noch offen bis zur Freigabe: KI nur bei Bedarf (`llm-dienst.sh`, macht `ops/run.sh`).
  Danach prüfen, ob `updateErlaubt`/`updateDran` so ankommen, wie `absprache.py` sie liest.
- Architektur-Check (`docs/ARCHITEKTUR-CHECK.md`, 27.11.): Vorschläge warten auf Kevins Freigabe. 1, 2 (App-Seite), 8, 9 und Media3 1.8.0 erledigt; als Nächstes Equalizer, eigene Musik vom Handy. Rechtssicherheit: Abschnitt 11 (keine Liedtexte, kein Mitschnitt, kein Weiterleiten).
- Noch keine 12 Tester für den geschlossenen Test. Ideen: `docs/TESTER.md` (Google-Gruppe als Testerliste, Link per WhatsApp, Tester-Tausch).

## Verlauf

- 24.11.: Android Auto abgesichert, Hit-Mix wartet richtig, Stopp bleibt Stopp (1.0.126).
- 24.11.: Weiterspielen nach dem letzten Lied, Tippen beim Start, Wächter, Wecker, Suche ohne Ruckeln (1.0.128).
- 26.11.: Server richtet sich nach Valdrakor, Senderzahl nach Vorgabe, Serverabsprache (budget.hitradar.level, Meldung in dienste/hitradar.json), Prüfregel Geheimnisse, Neuladen der Senderliste nicht mehr alle 30 Minuten.
- 26.11.: Builds geprüft, alle grün. Hochladen macht der tägliche Zeitplan (läuft bei GitHub meist erst abends), daher kein zusätzlicher Upload von Hand am selben Tag. Tester-Ideen in `docs/TESTER.md`.
- 26.11.: Android Auto: Zwischenspeicher (Suche, Einträge, Listen) mit Obergrenze, alte Suche wird abgebrochen, höchstens 40 Radiotreffer. Nachrichten fallen nie mehr auf den Hit-Mix zurück (sonst Deutschlandfunk live). Notfall-Sender fest eingebaut, damit Auto nie „Auswahl konnte nicht geladen werden“ zeigt (auch ohne Server und ohne Senderliste). Playlists/Hit-Mix mit Fehler: trotzdem Musik.
- 26.11.: App allgemein: Wächter startet nach Absturz/Neustart wieder (statt hängender Anzeige), kein Absturz beim Schließen mit offenem Fenster, alter Player nach onStop wird nicht mehr benutzt, Wecker wird nach App-Update und Zeit-/Zeitzonenwechsel neu geplant, LiveIndex wirft alte Einträge weg (Speicher).
- 26.11.: Hit-Mix: gestoppter oder ersetzter Mix schaltet nie mehr von selbst um (Zähler je Start/Stopp), kein doppelter Mix bei schnellem Doppeltippen, Sprungbremse zählt nur echte Sprünge, keine zwei Sprünge gleichzeitig, Playlist-Lieder nur einmal laden, Stopp während des Starts spielt nichts ab.
- 26.11.: Server-Updates nur nach Absprache: "update" melden, auf updateErlaubt warten (nicht bei updateDran "spiel"), danach "leicht"; bei pause "ruhe" (radar-update.sh, absprache.py, Workflow wartet mit). Server holt main per fetch + reset --hard (main kann neu geschrieben sein). SSH zum Server lief um 19 Uhr in Zeitüberschreitung.
- 26.11.: Android Auto: Hörspiel-Ordner bleiben auch nach einem Neustart der App gefüllt (Shows werden gespeichert).
- 26.11.: Prüfregel Commit-Texte: Pushes mit Mitautor- oder Sitzungs-Zeilen werden rot. Ein Commit nachträglich bereinigt (main neu geschrieben, Inhalt gleich): alle Arbeitskopien mit `git pull --rebase` holen.
- 26.11.: App sparsamer: Werbe-Prüfung (jede Sekunde) und Positions-Speichern laufen nur noch während der Wiedergabe bzw. in den ersten ~3 Minuten nach Senderwechsel; Widget nur bei echter Änderung neu zeichnen; Hit-Mix baut die Liste „Als Nächstes“ nicht mehr alle 20 s neu, wenn sie gleich bleibt (weniger Arbeit für Android Auto); Server-Abgleich übernimmt nur geänderte Sender komplett; Verbindungen zum Server bleiben offen (Keep-Alive, kein neuer Verbindungsaufbau je Abfrage, entlastet auch den Server).
- 26.11.: Hitradar sparsamer: ein Verteiler statt 250 Abfrager (kaum noch Leerlauf-CPU), KI startet nur bei Bedarf (spart ~1 GB RAM), ehrliche cpu/ramMb stündlich gemeldet.
- 26.11.: Anmeldung mit "unit":"radar.service"; Hinweise des Spiels (hinweise/hitradar.json) werden täglich gelesen und in /health gezeigt. Update-Freigabe auch aus /api/last, falls lage.json fehlt (nach Neustart des Servers fehlte sie). Kurze Aussetzer von /api/last lassen die Stufe nicht mehr hin und her springen.
- 27.11.: Architektur-Check: Bauweise passt (Kotlin/Media3 + ein sparsamer Server), Vorschläge in `docs/ARCHITEKTUR-CHECK.md`. „App bauen“ startet nicht mehr bei reinen Doku-Änderungen (`docs/`, `*.md`, `alexa/`). README aktualisiert.
- 27.11.: Server-Adressen aus `config/servers.json` (App liest täglich, eingebaute als Rückfall). Zwischenspeicher für seltene Server-Antworten auf dem Handy. Kein Signier-Schlüssel mehr im Repo, Release nur mit Upload-Secret.
- 27.11.: Server: Shoutcast-2-Statusseite (`/statistics?json=1`) vor dem Anzapfen des Streams (weniger Daten/CPU). Wird erst nach Neustart über radar-update.sh aktiv.
- 27.11.: Media3 1.8.0 (Bau grün).
- 27.11.: Rechtssicher: Server-Mix (/mix) und Weiterleitung (/relay) abgeschaltet, Alexa spielt Sender direkt (nur https), Casten nur https-Sender, Archiv nur CC/gemeinfrei (ohne Great 78), Lizenzliste ergänzt.
- 27.11.: radar-update erkennt, wenn radar schon anders neu gestartet wurde (kein unnötiger Neustart). Play-Upload vom 26.11. abends erfolgreich.
