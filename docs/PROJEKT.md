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

- Android Auto, Hit-Mix, Playlists, Nachrichten im Auto testen (nur gebaut, nicht im Auto geprüft). Ebenso Klang (Equalizer) und Klang-Erkennung (YAMNet).
- Prüfen, ob Hitradar `budget.hitradar.level` richtig liest, sobald Valdrakor es liefert (`/health` → `modus_quelle`).
- Server wartet auf das nächste Valdrakor-Update: `lage.json` fehlt, `/api/last` hat noch kein `updateErlaubt` (Stand 27.11. 8 Uhr).
  radar läuft seit 27.11. 7:43 schon mit dem neuen Stand (vom Spiel neu gestartet; Verteiler, Shoutcast, rechtssicher, Hinweise),
  radar läuft als root und schreibt `hitradar.json` selbst. Noch offen bis zur Freigabe: KI nur bei Bedarf (`llm-dienst.sh`, macht `ops/run.sh`).
  Danach prüfen, ob `updateErlaubt`/`updateDran` so ankommen, wie `absprache.py` sie liest.
- Architektur-Check (`docs/ARCHITEKTUR-CHECK.md`, 27.11.): Vorschläge warten auf Kevins Freigabe. 1, 2 (App-Seite), 8, 9 und Media3 1.8.0 erledigt; Equalizer („Klang“) erledigt; als Nächstes eigene Musik vom Handy. Rechtssicherheit: Abschnitt 11 (keine Liedtexte, kein Mitschnitt, kein Weiterleiten).
- Suche/Mix nach Richtung am Handy prüfen (z. B. „Rock“ eingeben, Rock-Mix 30 Min. laufen lassen), Wecker ohne Netz testen.
- Server (27.11. 15:39, Prüfung mit echten Daten): von 697 Sendern kennt der Server bei 0 den genauen Liedanfang („exact“), `/sync` braucht ~4,8 s. Vermutlich gedrosselt (Spar-Modus vom Spiel). Folge: der Mix verlässt sich auf das eigene Abfragen am Handy. Im Server-Thread prüfen.
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
- 27.11.: Klang-Voreinstellungen (Equalizer des Handys, `Klang.kt`): Aus, Mehr Bass, Klare Stimmen, Auto, Mehr Höhen.
- 27.11.: Langsamer Hit-Mix: Server fragte ~10.000 Sender reihum ab (bei den Grenzen des Spiels nur ~5 Abfragen/s), jeder Sender kam nur alle ~30 Min. dran, kein Liedanfang war bekannt. Jetzt Kernliste: Musik-Sender (DE/AT/CH zuerst) alle ~80 s, der Rest selten, gleiche Last. /health zeigt kern, abfragen_s, anfang_bekannt. App: Hit-Mix lädt Lieder, Genre-Sender und Server-Stand gleichzeitig, Weihnachts-/Kinder-Mix spielt auch Lieder mit unbekanntem Anfang statt nichts, leere Genre-Senderlisten werden 30 Min. gemerkt.
- 27.11.: Klang-Erkennung YAMNet auf dem Handy (`SoundSense.kt`): Werbung am Senderanfang stumm, bei erkannter Musik sofort laut. Nur kurz nach dem Umschalten aktiv, abschaltbar. Am Gerät testen!
- 27.11.: Hit-Mix wie ein Musikplayer: Lieder mit weniger als 1,5 Min. Rest werden nicht angesteuert; bei sekundengenau bekanntem Liedanfang (Titelwechsel im Stream) wird 1 s vor Liedende umgeschaltet; Klang-Erkennung läuft im Hit-Mix immer mit, Sprache zwischen Liedern (Moderation, Jingle, Werbung) wird sofort stumm und nach 3 s weitergeschaltet (mitten im Lied nie, z. B. Rap); Wechsel wird leise eingeblendet (nicht beim Casten); Zurück-Taste (Auto, Benachrichtigung, Kopfhörer) springt zum vorherigen Sender, live mit dem Lied, das dort jetzt läuft; Sender mit Herz: Mix bleibt dort, solange Musik läuft (nicht bei Weihnachten/Kinder); aktuelle bekannte Lieder stärker bevorzugt, bei Ära-Mixen (80er, 90er, 2000er, Oldies, Klassiker-Playlists) nicht; Kinder-Mix mit viel mehr bekannten Kinderliedern (Deezer-Suchen, Erkennungsliste inkl. Volkslieder und Disney) und mehr Kindersendern (App und Server-Liste).
- 27.11.: Hit-Mix: kürzere Lücke beim Umschalten. Vorladen nur kurz vor dem geplanten Wechsel (nächster Sender wird ~8 s vorher gewählt, Verbindung mit höchstens 8 KB aufgebaut), Player startet nach 1 s Puffer statt 2,5 s und lädt höchstens 20 s vor (spart Daten beim Umschalten), Sender, die lange zum Starten brauchen, werden gemerkt und seltener gewählt. Echtes Vorpuffern mit zweitem Player bewusst nicht (doppelter Datenverbrauch, Session/Equalizer hängen am Player).
- 27.11.: Zeitplan-Lauf von „App bauen“ lädt nicht mehr hoch, wenn in den letzten 20 Stunden schon von Hand hochgeladen wurde (höchstens 1 Upload am Tag).
- 27.11.: Hit-Mix schaltet nicht mehr fälschlich um: Slogan/Sendername als Titel mitten im Lied wird ignoriert, solange die Klang-Erkennung Musik hört; anders geschriebener Titel (Radio Edit, feat.) gilt als dasselbe Lied; Liedende laut Länge und veralteter Server-Stand schalten nicht um, solange noch Musik läuft (höchstens 20 s bzw. 1 Min. länger).
- 27.11.: Qualitätsrunde Suche/Mix: Suche nach einer Richtung („Rock“, „Rockmusik“, „hard rock“, „80er Hits“) zeigt nur diese Richtung (Mix-Knopf oben, Top-Songs der Richtung, passende Sender, freie Musik per Tag); „Rock DJ“, „Kid Rock“, „Metallica“ bleiben Lied-/Künstlersuche. Gleiches in Android Auto (Suche und „Spiel Rockmusik“). Regeln in `MixLogic.kt`, Tests in `app/src/test` (laufen bei „App bauen“ vor dem Bauen).
- 27.11.: Hit-Mix spielt keine falsche Musik mehr: Genre-Mixe ohne deutsche Pop-Charts (vorher Pop im Rock-Mix), nie „irgendeine Musik“ bei festen Mixen (Genre, Künstler, Playlist), Sender werden nach Tags geprüft (Hit-Sender mit „pop, rock, hits“ zählt nicht als Rocksender), Lieblingssender halten nur offene Mixe fest, Ersatz ist ein Sender der Richtung statt irgendeines Senders. Kein Umschalten mehr, wenn der Sender seinen Namen als Künstler schickt („ROCK ANTENNE - Wir rocken Bayern“). Lieder werden über ganze Wörter erkannt („Up“ passt nicht mehr zu „Superstar“).
- 27.11.: Wecker: kommt nach 40 s kein Radio (kein Netz, Sender kaputt), klingelt der Weckton des Handys (höchstens 4 Min., aus beim Stoppen oder sobald Radio spielt).
- 27.11.: Server `/list?t=tag:…`: ganzes Wort im Tag, DACH zuerst, mit Tags in der Antwort (App sortiert selbst aus). Aktiv nach dem nächsten Neustart über radar-update.sh.
- 27.11.: Wie große Musik-Apps: Funkloch/Tunnel/Netzwechsel überspringt keine Lieder mehr, Radio und Lieder verbinden von selbst neu (wachsende Pause bis 20 s, sofort wenn Netz zurück, höchstens 20 Min.); Hit-Mix springt ohne Netz nicht von Sender zu Sender. Radio länger als 30 s pausiert: beim Weiterspielen wieder live (kein alter Puffer, Liedzeit passt). Nach Werbung/Ansage-Stummschaltung und Mix-Wechsel geht die Lautstärke zurück auf den Wecker-Pegel statt sofort auf voll.
- 27.11.: 1.0.169 (Stand wie 1.0.168) als zweiter Upload des Tages in den internen + geschlossenen Play-Test hochgeladen (Kevin hat freigegeben).
- 27.11.: Liedzeit: ohne bekannten Liedanfang keine Zeit mehr (vorher „Läuft seit 0:05“ = seit Einschalten, sah aus wie Liedzeit). Zurück zu einem Sender stellt den gemerkten Liedanfang wieder her. „Wächter“ heißt in der App jetzt „Abspielen, sobald es läuft“ / „Wunschlied“, weniger Hinweistexte.
- 27.11.: Bedienung: Player aufgeräumt („Mehr davon“, Teilen, Daumen runter im •••-Menü), kurze Erklärung beim ersten Hit-Mix, Daumen runter für Lied oder Künstler (`Dislikes.kt`, nur auf dem Handy, im •••-Menü wieder erlaubbar), Startseite „Weiterhören: Hit-Mix …“ mit dem letzten Mix, Suchvorschläge für Musikrichtungen beim Tippen, Lieder mit unbekanntem Anfang, die schon länger laufen, gelten als fast vorbei, gleiche Lautstärke zwischen Sendern (Pegel je Sender per Klang-Erkennung gemessen und gemerkt, Ausgleich ±8 dB über den Normalisierer, Android 9+).
- 27.11.: Liedzeit: laut.fm-Sender liefern Anfang/Ende sekundengenau (`LautFm.kt`, Handy fragt direkt); sonst „Lied läuft seit mind. …“ (Server schickt bei unbekanntem Anfang, wann er das Lied zuerst gesehen hat); keine volle Leiste mit -0:00 mehr, wenn das Lied länger läuft als gedacht. „Jetzt im Radio“ lädt neu, statt bei „Schaue nach…“ hängen zu bleiben. „Beliebt bei Hörern“ erst ab 5 Liedern. „Sender aus welchen Ländern“ statt „Sender aus“. Ab 20.11. Weihnachts- statt Herbst-Playlists. Kinder-Hörspiele nur aus „Kinder und Familie“. Gleicher Sender in anderer Qualität nur einmal unter „Zuletzt gehört“.

- 27.11. abends: Gesamtprüfung (3 unabhängige Durchsichten + Nachprüfung, ~85 Punkte), kein Upload. Behoben u. a.: Neu-Verbinden im Funkloch und „wieder live nach Pause“ griffen nie (Player-Vergleich falsch), Liedzeit nach Jingle verloren, Stummschaltung blieb hängen, Werbung zu früh laut, Wecker-Ersatzton per Taste aus, Casten mit http-Sendern (falscher Sender/Stille), Lautsprecher weg = Pause statt laut am Handy, Schlaf-Timer-Ausblenden, Schlaflied-Lautstärke. Hit-Mix: Künstler-/Playlist-Mix spielt keine fremden Chart-Hits, Künstler nur im Künstler-Teil („ABBA – Dancing Queen“ ist kein Queen), verschiedene Lieder langer Künstlernamen nicht mehr „gleich“, unbekannter Liedanfang nicht mehr „zu lang“, Stumm bei Sprache max. 90 s, Serverausfall während des Mix, schnellere Sortierung. Liedanfang: erstmals gesehen = unbekannt (nicht „gerade angefangen“), Server-Uhr abgeglichen. App: altes angetipptes Lied überstimmt keine neue Wahl, Kinder-Modus bleibt bei Kinderradios, PIN zweimal + „PIN vergessen?“, Laden mit Fehler hängt nicht mehr, Zurück-Taste wie große Musik-Apps, Design folgt Handy, Wunschlied-Texte ehrlich („Bescheid sagen“), technische Texte ersetzt. Tests: sameSong-Fälle ergänzt.
- 27.11. abends: Leistung/Suche: Suche findet ganze Wörter und Wortanfänge (nicht mehr mitten im Wort, „up“ ≠ „Superstar“), zusammengeschrieben („acdc“), und wenn nichts passt mit kleinen Tippfehlern (ab 5 Buchstaben 1, ab 8 zwei; `MixLogic.searchMatches`, getestet). Laufende Titel werden über den Wort-Index im Hintergrund durchsucht. Vorschläge beim Tippen nach 450 statt 700 ms, Suchantworten 10 Min. gemerkt (weniger Netz und Serveranfragen). Klang-Erkennung im Hit-Mix mitten in Liedern mit bekannter Länge aus (Akku). Ohne Server mobil viel weniger Sender-Abfragen im Mix (12 statt 30 je 20 s, Genre 12 je 60 s) und bei großem ICY-Abstand nur ein Block.
- 27.11. 15:00: 1.0.177 (Stand wie 1.0.176: Gesamtprüfung + Suche/Leistung) in den internen + geschlossenen Play-Test hochgeladen (Kevin hat freigegeben, dritter Upload des Tages).
- 27.11. nachmittags (nach Test von 1.0.177): Weiterschalten bringt nie mehr denselben Sender (auch nicht in anderer Qualität) oder das gerade gelaufene Lied (auch anders geschrieben); die letzten Lieder auch dann nicht, wenn Wiederholungen erlaubt sind. Sprecher/Werbung im Hit-Mix: viele Sender lassen den Liedtitel stehen – jetzt reicht „überwiegend Sprache“ über 8 s (mitten im Lied mit bekannter Länge 16 s, frühestens 45 s nach Liedbeginn), dann stumm und weiter (`SoundSense.mostlyTalk`). Liedende-Umschalten auch mit Anfang vom Server. Klang-Erkennung pausiert nur noch bis 70 % der Liedlänge. Liedzeit: Versatz „Server sieht den Wechsel früher, als man ihn hört“ wird je Sender gemessen (`Latency.kt`) und beim Einschalten eingerechnet; kurze Pause schiebt die Liedzeit mit; nach Neu-Verbinden zählt der nächste Titel nicht als selbst gehörter Anfang.
- 27.11. später Nachmittag (Kevin: „bitte kontrolliere nochmal was ich alles bemängeln könnte“), kein Upload: Täglicher Auto-Upload am Morgen pausiert (`build.yml`, `schedule` auskommentiert). Kritische Durchsicht aus Nutzersicht (39 Punkte), behoben: rechts steht die Gesamtlänge des Lieds (3:22) statt Restzeit; Länge bekannt, Anfang nicht: Leiste ab „mind. 1:05“; Mix-Status („ich warte auf den nächsten“) im Untertitel; „Zurück“ im Player auch im Mix (`HitMix.canGoBack`); Weiter im Mix schaltet immer (notfalls anderer Sender der Richtung), Wiederholsperre 30 Lieder; keine Werbeanzeige, wenn hörbar Musik läuft; Werbung nur noch bei Sendern mit Liedtiteln gelernt; „Mix beendet – X läuft normal weiter“; Weiter nach einzeln getipptem Sender bleibt in der Richtung (`MixLogic.mainGenre`) bzw. bei Kindersendern; Kindermodus auch in Android Auto und nach freien Liedern; strengere Kinder-Podcast-Auswahl; Einschlafen nutzt Gute-Nacht-Geschichten; Archiv-Musik heißt jetzt `am:` (nicht mehr Hörspiel); Folgen neueste zuerst außer Serien (`itunes:type serial`, Hörbücher); Liedtitel in schöner Schreibweise; Quelle „HÖRSPIEL & PODCAST“ / „HIT-MIX“; Verlauf mit Sendername; Mix-Sender nicht in „Zuletzt gehört“; Texte (Lieder statt Songs, „Hit-Mix: X“, Wecker mit Bestätigung, Playlists ohne Doppelte, Deutsch statt Englisch, „Gute Laune“ = Pop). Prüfung mit echten Daten: `.github/workflows/sim.yml` + `tools/sim` (nur von Hand).
- 27.11. ~17 Uhr: Echte Daten (`sim.yml`): Sendernamen/Programme als Titel („RADIO BOB - Classic Rock“, „SUNSHINE LIVE - Techno“, „Rockland Radio - Neuwied“) zählen nicht mehr als Lied (`MixLogic.isProgramTitle`, Markennamen aller Sender in `LiveIndex.brands`). Liedanfang aus einem beim Abgleich gesehenen Wechsel (Lücke ≤ 150 s), auch wenn der Server ihn nicht kennt. Build 1.0.182 grün, nicht hochgeladen.
