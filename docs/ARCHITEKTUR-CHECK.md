# Hitradar – Architektur-Check

Stand: 27.11.2026 (Version 1.0.14x). Frage von Kevin: Ist die Bauweise die richtige für einen **kostenlosen, vollwertigen
Musikspieler**, der **sehr wenig Serverlast** zieht und trotzdem sehr gut läuft? Was könnte man anders oder besser machen?

Geprüft: App-Code (Kotlin, ~8.300 Zeilen), Server (`radar.py`, ~1.750 Zeilen), Datenquellen, Wiedergabe, Workflows (Bauen, Play, Server).

---

## 1. Kurzfazit

**Die Grundbauweise ist richtig und sollte bleiben.** Kein Neubau nötig.

| Bereich | Bewertung | Warum |
|---|---|---|
| App-Technik (Kotlin + Media3, nativ) | 🟢 richtig | Android Auto, Hintergrund, Casten, Widgets gehen nur nativ wirklich sauber. Flutter/React Native wären hier schlechter. |
| Server als „Radar“ | 🟢 richtig | „Was läuft gerade wo“ braucht jemanden, der rund um die Uhr tausende Sender beobachtet. Das auf jedem Handy zu machen kostet Akku und Daten. Ein Server für alle ist die sparsamste Lösung. |
| Server-Sparsamkeit | 🟢 gut | Ein Verteiler, Icecast-Statusseiten, Diff-Abgleich (`/sync`), 15-s-Antwort-Cache, gzip, KI nur bei Bedarf, Stufen vom Spiel. Schon weit optimiert. |
| Abhängigkeit vom Server | 🟡 Risiko | Server-Adresse (IP) fest in der App. Zieht der Server um, sind alle alten Apps blind. Größtes Architektur-Risiko. |
| „Vollwertiger Musikspieler“ | 🟡 Lücken | Keine eigenen Musikdateien vom Handy, kein Offline, kein echter Equalizer, keine Liedtexte. |
| App-Code-Aufbau | 🟡 wird schwer wartbar | `MainActivity.kt` hat ~2.900 Zeilen, keine Tests. Läuft, aber jede Änderung ist riskanter. |
| Build/Play-Weg | 🟡 ok, aber eng | GitHub Actions kostenlos und gut. Play braucht 12 Tester; es gibt freie Alternativen (IzzyOnDroid, F-Droid, Obtainium). |
| Sicherheit | 🟡 eine Baustelle | Ein Signier-Schlüssel mit Passwort liegt im öffentlichen Repo (nur als Rückfall, siehe 7.1). |

---

## 2. Top-Empfehlungen (nach Nutzen / Aufwand)

Nutzen und Aufwand: ★ = wenig, ★★★ = viel.

| # | Empfehlung | Nutzen | Aufwand | Serverlast |
|---|---|---|---|---|
| 1 | **Server-Adresse nicht mehr fest einbauen**: kleine Datei `config.json` über GitHub Pages / raw.githubusercontent (kostenlos, weltweit gecacht). App liest sie einmal am Tag, fällt auf eingebaute Adressen zurück. | ★★★ | ★ | – |
| 2 | **HTTP-Zwischenspeicher in der App** (`HttpResponseCache.install`) + **`Cache-Control`-Header** am Server für `/charts`, `/popular`, `/rising`, `/ads`, `/list`, `/cover`. Gleiche Anfrage wird dann gar nicht erst gestellt. | ★★ | ★ | ⬇⬇ |
| 3 | **Eigene Musik vom Handy abspielen** (MediaStore, Ordner, Alben, Künstler). Das ist der größte Unterschied zu einem „vollwertigen Musikspieler“. Kostet null Server. | ★★★ | ★★ | – |
| 4 | ~~Liedtexte über LRCLIB~~ **gestrichen** (Liedtexte sind geschützt, LRCLIB hat keine Lizenz der Verlage, siehe Abschnitt 11). | – | – | – |
| 5 | **Echter Equalizer** (Android `Equalizer`/`DynamicsProcessing` ist schon im Code für Lautstärke-Ausgleich). Bänder + Voreinstellungen (Bass, Auto, Sprache). | ★★ | ★ | – |
| 6 | **Fallback-Server aktivieren**: Der Workflow „Server zu Hugging Face“ ist schon da (kostenloser Space), nur nicht eingeschaltet. Als zweite Adresse in `config.json`. | ★★ | ★ | verteilt |
| 7 | **Statt Play-Test (12 Tester) zusätzlich IzzyOnDroid + Obtainium** anbieten: kostenlos, keine Tester nötig, Updates direkt aus den GitHub-Releases. | ★★★ | ★ | – |
| 8 | **Signier-Schlüssel aus dem Repo nehmen** bzw. Release-Bau ohne Upload-Schlüssel abbrechen lassen statt mit dem öffentlichen Schlüssel zu signieren. | ★★ | ★ | – |
| 9 | **Shoutcast-Statusseiten am Server nutzen** (`/stats?json=1`, `/currentsong`, `7.html`) bevor der Stream selbst angezapft wird: kein Audio laden, viel weniger Daten und CPU. | ★★ | ★ | ⬇⬇ |
| 10 | **Abhängigkeiten aktualisieren** (Media3 1.5.1 → aktuell, Kotlin 2.0 → 2.2, AGP). Neue Media3-Versionen haben bessere Android-Auto- und Akkuwerte. | ★★ | ★ | – |
| 11 | **MainActivity aufteilen** (je Bildschirm eine Datei + ViewModel). Nicht neu schreiben, Stück für Stück. | ★★ | ★★ | – |
| 12 | **Automatische Tests** für reine Logik (Hit-Mix-Auswahl, Titel säubern, `normalize`, Abgleich `/sync`) im Workflow. Kostenlos, fängt Fehler vor dem Upload. | ★★ | ★★ | – |
| 13 | **Offline-Lieder** nur für eigene Dateien und freie Musik mit Download-Erlaubnis (Jamendo `audiodownload_allowed`, CC-/gemeinfreie Archiv-Aufnahmen). **Nie Radiomusik.** | ★★ | ★★ | – |
| 14 | **Wächter per Push statt Abfragen** (Server kennt eh alle Titel und meldet sich über Firebase Cloud Messaging, kostenlos). Spart Akku, Handy muss nicht jede Minute fragen. | ★★ | ★★★ | ⬆ wenig |

Schon umgesetzt in diesem Check: siehe Abschnitt 9.

---

## 3. App-Technik

### Was passt
- Kotlin nativ + **Media3** (`MediaLibraryService`) ist genau der von Google vorgesehene Weg für Android Auto, Sperrbildschirm, Bluetooth-Anzeige, Uhr und Casten.
- R8 (verkleinern) an, Mapping-Datei geht an Play → lesbare Absturzberichte.
- Viel Sparsamkeit bereits drin: Keep-Alive, Arbeit nur bei Wiedergabe, Listen im Hintergrund berechnen.

### Alternativen geprüft
| Alternative | Urteil |
|---|---|
| Flutter / React Native | ❌ Android Auto, Media-Session, Widgets nur über Zusatzpakete, mehr Akku, mehr Fehlerquellen. Kein Gewinn. |
| Jetpack Compose statt XML-Layouts | 🟡 Moderner und weniger Code, aber großer Umbau. Erst bei neuen Bildschirmen nutzen, nicht alles umbauen. |
| Kotlin Multiplatform (iPhone mit) | 🟡 Nur falls iPhone ein Ziel wird. Dann Logik (Hit-Mix, Server-Abgleich) in ein gemeinsames Modul. |

### Verbesserungen
1. **MainActivity (~2.900 Zeilen) aufteilen**: `HomeScreen`, `SearchScreen`, `LibraryScreen`, `SettingsScreen` + je ein ViewModel. Weniger Abstürze bei Drehen/Fenstern, leichter zu ändern.
2. **Daten speichern**: Favoriten, Playlists, Verlauf liegen als SharedPreferences (13 Dateien). Für größere Listen **Room** (SQLite) – schneller, sicher bei Absturz, durchsuchbar.
3. **Sicherung/Umzug**: Playlists/Favoriten als Datei exportieren/importieren; Android-Auto-Backup gezielt einstellen (`dataExtractionRules`), damit es beim Handywechsel mitkommt.
4. **Netzwerk**: `HttpURLConnection` reicht, aber **OkHttp** (+ `media3-datasource-okhttp`) bringt HTTP/2, gemeinsamen Verbindungs-Pool für App *und* Player, eingebauten Cache. Mittlerer Aufwand.
5. **Eigene Threads** (`Thread { … }.start()` für Meldungen) durch einen gemeinsamen Coroutine-Scope ersetzen; Meldungen (`/event`, `/ad`) **sammeln** und alle paar Minuten gebündelt schicken → weniger Anfragen an den Server.
6. **Startzeit**: Baseline-Profile (kostenlos, Google-Plugin) – App startet spürbar schneller.
7. **Abhängigkeiten** regelmäßig aktualisieren (Dependabot einschalten oder einmal im Monat von Hand).
8. **Absturz-Überblick**: Play Console „Android Vitals“ regelmäßig ansehen (kostenlos). Ohne Play: ACRA mit Bericht per Mail.

---

## 4. Server-Abhängigkeit und Serverlast

### Was passt
- Ein Server für alle ist sparsamer als jedes Handy einzeln.
- Schon gut: ein Verteiler, Icecast-Statusseite (ein Abruf für viele Sender), Abfragezeit nach Liedlänge, `/sync` nur Änderungen, fertig gepackte Antworten 15 s wiederverwendet, kein Zugriffsprotokoll, KI nur bei Bedarf, Regeln vor KI bei „Wünsch dir was“, Stufen vom Spiel.
- App kann ohne Server weiter (scannt selbst, Notfall-Sender) – sehr gut.

### Risiken
1. **Feste IP in der App** (`130.61.78.232`, `sslip.io`): Umzug oder neue IP → alle installierten Apps finden den Server nicht mehr. → **Empfehlung 1** (`config.json` über GitHub Pages). Zusätzlich eine kostenlose eigene Adresse (z. B. DuckDNS) statt IP-Name.
2. **Ein einziger Server**, den sich Hitradar mit dem Spiel teilt. → Hugging-Face-Space als Rückfall (Workflow vorhanden, `HF_SPACE` setzen).
3. **Unverschlüsselter Rückfall** `http://130.61.78.232`: harmlose Daten, aber Mobilfunkanbieter/WLANs können sie verändern. Mittelfristig nur https.

### Weniger Serverlast – Ideen (nach Wirkung)
| Idee | Wirkung | Aufwand |
|---|---|---|
| **Shoutcast-Statusseiten** (`/stats?sid=1&json=1`, `/currentsong?sid=1`, v1 `7.html`) vor dem Anzapfen des Streams. Heute wird bei jedem Nicht-Icecast-Sender Audio geladen (bis ~32 KB je Abfrage, alle ~75 s). | groß (Daten + CPU) | ★ |
| **Sender-APIs, die alles auf einmal liefern**: z. B. laut.fm hat eine öffentliche API mit aktuellem Lied je Sender; große Sendergruppen (ARD-Anstalten, Regiocast, Antenne) haben „Jetzt läuft“-JSON. Ein Abruf statt hunderter Stream-Anzapfungen. | groß | ★★ |
| **Nach Bedarf abfragen**: Sender, die nie gefunden/gehört werden und keine Musik spielen (Wort, Nachrichten), nur noch selten prüfen; beliebte/gesuchte Sender öfter. | mittel | ★ |
| **`Cache-Control` + ETag** an den Antworten; App mit HTTP-Cache (Empfehlung 2). Charts/Beliebt/Werbelängen ändern sich selten. | mittel | ★ |
| **Fertige Dateien statt Python**: Snapshot (`/sync` voll, `/charts`, `/popular`) als vorgepackte Datei schreiben und von Caddy direkt ausliefern lassen. Python rechnet nur noch alle 15 s einmal. | mittel | ★★ |
| ~~Weiterleitung fürs Casten~~ (`/relay`, `/mix`): **abgeschaltet** aus rechtlichen Gründen (Abschnitt 11). Spart nebenbei den einzigen Dauer-Datenverkehr. | erledigt | – |
| **Meldungen bündeln** (`/event`, `/ad`) statt jede einzeln. | klein | ★ |
| **KI**: Regeln zuerst ist schon so. Häufige Wünsche dauerhaft speichern (Cache übersteht Neustart), dann startet die KI noch seltener. | klein | ★ |
| **Server-Code aufteilen** (`radar.py` 1.750 Zeilen: Abfrage, Diff, Alexa, Relay, KI in eigene Dateien). Kein Leistungsgewinn, aber sicherere Updates. | Wartung | ★★ |

### Nicht empfohlen
- **„Ganz ohne Server“** (nur Handy): Hauptfunktion (Lied finden, Hit-Mix) wäre langsam und frisst Akku/Daten.
- **Bezahl-Clouds/Serverless** (Firebase Functions, Cloud Run mit Rechnung): verstößt gegen „alles kostenlos“, kann automatisch Kosten machen.
- **WebSocket/Dauerverbindung für alle**: hält viele offene Verbindungen am Server; der jetzige Diff-Abgleich alle 2–5 Minuten ist sparsamer.

---

## 5. Datenquellen

| Quelle | Wofür | Bewertung / Verbesserung |
|---|---|---|
| radio-browser.info | Senderliste (App direkt + Server) | 🟢 frei. Verbesserung: Server-Liste über `all.api.radio-browser.info` (DNS) holen statt 3 feste Namen; beim Abspielen den „Klick“ melden (`/json/url/<id>`) – so wünscht es das Projekt, hilft der Gemeinschaft. |
| Icecast/ICY | Was läuft | 🟢 + Shoutcast-Statusseiten ergänzen (Abschnitt 4). |
| Deezer | Charts, Cover, Lieddauer | 🟡 öffentliche API ohne Schlüssel, Nutzungsbedingungen nur für nicht-kommerziell geduldet. Rückfall einplanen: **MusicBrainz + Cover Art Archive** (frei, offen) für Cover/Dauer. |
| Jamendo | Freie Lieder | 🟢 frei (nicht-kommerziell). Eine Client-ID für alle Nutzer → bei vielen Nutzern Tageslimit möglich; Ergebnisse in der App länger zwischenspeichern. |
| Audius, Internet Archive | Freie Lieder | 🟢 frei. |
| iTunes-Suche | Podcasts finden | 🟡 geht, aber Apple. Freie Alternativen: **Podcast Index** (kostenloser Schlüssel) oder **fyyd** (deutsch, ohne Schlüssel). |
| ~~LRCLIB~~ | Liedtexte | 🔴 nicht nutzen: Texte ohne Verlagslizenz (Abschnitt 11). |
| **Neu: ListenBrainz/Last.fm** | „Gehörtes“ teilen (Scrobbeln), Empfehlungen | 🟡 freiwillig, kostenlos. |
| **Neu: eigene Dateien** (MediaStore) | Vollwertiger Player | 🟢 kein Server, kein Netz. |

---

## 6. Wiedergabe (Musikspieler-Umfang)

### Schon da
Hintergrund, Android Auto (Listen, Suche, Sprache), Casten, Schlaf-Timer, Wecker mit Einblenden, Werbung stumm, Lautstärke-Ausgleich, Weiterspielen nach dem letzten Lied, Widgets, Hörspiele/Hörbücher/Podcasts, Playlists, Favoriten, Kindermodus mit PIN.

### Fehlt für „vollwertig“ (nach Wichtigkeit)
1. **Eigene Musikdateien** (Ordner, Alben, Künstler, Genres, Suche) – inkl. Android Auto.
2. **Offline**: nur eigene Dateien, freie Lieder mit Download-Erlaubnis und Podcast-Folgen (Media3 `DownloadManager`). Keine Radiomusik.
3. **Equalizer** mit Bändern und Voreinstellungen (Technik schon im Code: `DynamicsProcessing`).
4. ~~Liedtexte~~: nur mit lizenziertem Anbieter (kostet Geld) – daher nicht.
5. **Warteschlange bearbeiten** (verschieben, entfernen, „als Nächstes“ für eigene Lieder).
6. **Übergänge**: lückenlos (Media3 kann das bei Dateien) und optional Überblenden zwischen Liedern.
7. **Wiedergabe-Tempo** für Podcasts/Hörbücher (0,8–2×), Kapitel, „Folge gehört“-Merker, Position je Folge.
8. ~~Sender aufnehmen~~: **nicht** einbauen (Mitschneiden von Radiomusik in einer App ist rechtlich heikel). Höchstens kurzer Puffer im Arbeitsspeicher zum Zurückspulen.
9. **Lautstärke-Normalisierung je Lied** (ReplayGain-Tags bei eigenen Dateien).
10. **Wear OS / Uhr-Steuerung** kommt über Media3 automatisch; prüfen, ob es schön aussieht.

### Technik-Hinweise
- Player-Puffer (`LoadControl`) für Radio kleiner, für Dateien größer → weniger Daten bei kurzem Reinhören.
- Gemeinsamer HTTP-Stack für Player und App (OkHttp) spart Verbindungen.
- Auf Audio-Fokus und Bluetooth-Wechsel achten (ist mit `handleAudioFocus` und `BecomingNoisy` schon richtig).

---

## 7. Build- und Play-Weg

### Was passt
- GitHub Actions: für öffentliche Repos kostenlos, baut ohne PC, lädt automatisch in Play. Gut für Kevin (nur Handy).
- Upload höchstens einmal am Tag, Prüfregeln für Geheimnisse und Commit-Texte.

### Verbesserungen
1. **Nicht bei jeder Doku-Änderung bauen** – umgesetzt (Abschnitt 9).
2. **Weniger Releases**: jeder Push erzeugt ein GitHub-Release (schon ~148). Vorschlag: Release nur bei Play-Upload oder Tagesbau, alte automatisch löschen (z. B. nur die letzten 10 behalten).
3. **Gradle-Wrapper** ins Repo (`gradlew`), damit überall dieselbe Gradle-Version baut; Build-Cache ist durch `setup-gradle` schon an.
4. **Tests + Lint im Workflow** (Lint ist aus: `abortOnError = false`). Mindestens Lint-Bericht als Datei, damit Fehler sichtbar werden.
5. **Verteilwege ohne 12-Tester-Hürde**:
   - **IzzyOnDroid** (F-Droid-kompatible Quelle, nimmt GitHub-APKs, kostenlos).
   - **Obtainium** (App, die Updates direkt aus GitHub-Releases holt) – im README verlinken.
   - **F-Droid offiziell** nur ohne Google-Teile (Casten nutzt Play-Dienste) → bräuchte eine Variante ohne Cast.
   - **In-App-Update-Hinweis**: App prüft einmal am Tag das neueste GitHub-Release (nur für die GitHub-Version).
6. **versionCode = Lauf-Nummer** passt; nur beachten, dass ein Neustart des Workflows dieselbe Nummer erneut baut.

### 7.1 Sicherheit
- `app/radiofinder.keystore` liegt mit Passwort `radiofinder` im **öffentlichen** Repo. Er wird für Debug und als **Rückfall für Release** genutzt, wenn das Upload-Secret fehlt. Wer ihn kennt, kann eine APK bauen, die sich über eine so signierte Hitradar-Version installieren lässt.
  → Empfehlung: Release-Bau ohne Upload-Schlüssel **abbrechen** statt mit dem öffentlichen zu signieren; Debug-Schlüssel nicht mehr ins Repo (Android erzeugt sonst automatisch einen). Vorsicht: Nutzer, die eine mit dem alten Schlüssel signierte APK haben, müssen einmal neu installieren. Deshalb nur vorgeschlagen, nicht umgesetzt.
- `usesCleartextTraffic="true"` ist für viele Radiosender (nur http) nötig. Besser: per `network_security_config` erlauben, aber für den eigenen Server nur https.
- Content-Provider `ArtProvider` ist offen (nötig für Android Auto), liefert nur Bilder – ok.

---

## 8. Datenschutz und Kosten

- 🟢 Kein Zugriffsprotokoll am Server, Meldungen anonym und abschaltbar (`share`).
- 🟢 Alles läuft auf kostenlosen Diensten (Oracle Always Free, GitHub, freie APIs).
- 🟡 Oracle Always Free kann ungenutzte Rechner abschalten oder Konten prüfen → zweiter Weg (Hugging Face) und `config.json` schützen davor.
- 🟡 Firebase (für Push-Wächter) wäre kostenlos, bringt aber Google-Dienste in die App und Pflicht-Angaben in der Datenschutzerklärung.

---

## 9. In diesem Check umgesetzt (klein und sicher)

- Workflow „App bauen“ startet nicht mehr bei Änderungen nur an `docs/`, `*.md` oder `alexa/` (spart Bau-Zeit und unnötige Releases).
- README auf den aktuellen Stand gebracht (Name Hitradar, Dateiname `Hitradar.apk`, Play-Test erwähnt).
- **Empfehlung 1 umgesetzt:** Server-Adressen stehen in `config/servers.json`. Die App holt die Liste einmal am Tag
  (raw.githubusercontent.com, sonst jsDelivr), merkt sie sich und hängt die eingebauten Adressen immer als Rückfall an.
  Umzug = neue Adresse oben in `servers.json` eintragen.
- **Empfehlung 2 (App-Seite) umgesetzt:** Zwischenspeicher auf dem Handy für Antworten, die sich selten ändern
  (Charts/Beliebt/Neu 10 min, Senderlisten und „Mehr wie dieses“ 1 h, Werbelängen 6 h, gefundene Cover 7 Tage).
  Übersteht einen Neustart der App, ist der Server weg, gilt die letzte Antwort. Server unverändert
  (`Cache-Control` am Server bleibt Vorschlag für ein späteres Server-Update).
- **Empfehlung 8 umgesetzt:** `radiofinder.keystore` aus dem Repo entfernt. Release wird nur noch mit dem geheimen
  Upload-Schlüssel signiert, ohne ihn bricht „App bauen“ mit klarer Meldung ab. Der alte Schlüssel steht weiter in der
  Git-Geschichte und gilt als öffentlich, wird aber nicht mehr benutzt.
- **Empfehlung 9 umgesetzt (Server):** Hat ein Radioserver keine Icecast-Statusseite, fragt `radar.py` die
  Shoutcast-2-Statusseite (`/statistics?json=1`). Ein kleiner Abruf für alle Streams des Servers statt Audio anzuzapfen.
  Aktiv erst nach dem nächsten Neustart über `radar-update.sh` (Serverabsprache).
- **Empfehlung 10 (Teil) umgesetzt:** Media3 von 1.5.1 auf 1.8.0, Bau grün. Kotlin/AGP bleiben vorerst.

Alles andere sind Vorschläge. Große Umbauten (Code aufteilen, Room, eigene Dateien, Push-Wächter, Server-Änderungen) nur nach Freigabe
und Stück für Stück, damit Android Auto und Hit-Mix stabil bleiben. Server-Änderungen nur über die Serverabsprache.

## 10. Vorschlag Reihenfolge

1. Woche: Empfehlung 1, 2, 8, 10 (klein, schützen vor Ausfall).
2. Woche: Equalizer, IzzyOnDroid/Obtainium.
3. danach: eigene Musikdateien + Offline (größter Funktionsgewinn).
4. nebenbei: Shoutcast-Statusseiten und Bedarfs-Abfrage am Server (Serverlast runter).
5. laufend: MainActivity stückweise aufteilen, Tests ergänzen.

---

## 11. Rechtssicherheit (Prüfung 27.11.)

Vorgabe von Kevin: alles lizenzfrei bzw. rechtlich sauber, niemand soll wegen „Musik-Klau“ klagen können.
Das hier ist eine technische Einschätzung, keine Rechtsberatung. Bei echten Zweifeln: kurze Beratung, z. B. bei der
Verbraucherzentrale oder einem Anwalt für Urheberrecht (Erstberatung oft günstig).

### Grundsatz, nach dem die App jetzt gebaut ist
- **Radio wird nur empfangen, nie kopiert.** Die App spielt jeden Sender direkt von dessen eigenem Server ab, wie ein
  Internetradio. Hitradar speichert keine Radiomusik, schneidet nichts zusammen und sendet nichts weiter.
- **Hitradar speichert nur Fakten**: Titel/Künstler, die gerade laufen, Uhrzeiten, Zählungen. Das ist nicht geschützt.
- **Musik zum Abspielen auf Abruf** nur aus Quellen mit Erlaubnis: freie Lizenzen (Creative Commons, gemeinfrei),
  offizielle Schnittstellen der Anbieter (Jamendo, Audius, Deezer-Vorschau) und Podcasts aus ihren öffentlichen Feeds.

### Geändert (riskant → entfernt)
| Was | Warum riskant | Änderung |
|---|---|---|
| Server-Mix `/mix` (für Alexa) | Server schnitt Lieder verschiedener Sender zu einem neuen Dauer-Stream zusammen = eigenes „Programm“ aus fremder Musik, ohne Lizenz (GEMA/GVL). | **Abgeschaltet.** Alexa spielt jetzt den Sender direkt (nur https-Sender). „Weiter“ wählt einen anderen Sender, wie Umschalten. |
| Weiterleitung `/relay` (Casten) | Server reichte Radio-Streams durch = Weitersenden über eigenen Server. | **Abgeschaltet.** Casten nur noch bei Sendern mit https (direkt vom Sender), Hit-Mix wählt beim Casten nur solche Sender. |
| Internet Archive (freie Musik) | Im Archiv liegen auch hochgeladene Lieder ohne Erlaubnis und die „Great 78“-Sammlung (wegen der haben große Plattenfirmen das Archiv verklagt). | **Nur noch Einträge mit Creative-Commons- oder Gemeinfrei-Lizenz**, „Great 78“ (`georgeblood`) ausgeschlossen. LibriVox-Hörbücher sind gemeinfrei und bleiben. |
| Liedtexte (geplant) | Liedtexte sind urheberrechtlich geschützt; freie Seiten wie LRCLIB haben keine Verlagslizenz. | **Gestrichen.** Nur mit lizenziertem Anbieter (kostenpflichtig). |
| Sender aufnehmen / offline (geplant) | Mitschnitt von Radiomusik als App-Funktion ist heikel. | **Gestrichen.** Offline nur eigene Dateien und freie Musik mit Download-Erlaubnis. |
| Lizenz-Hinweise | Apache-2.0-Bibliotheken verlangen einen Hinweis. | Liste in „Open-Source-Lizenzen“ vervollständigt (MediaRouter, Palette, SwipeRefresh, Play-Dienste). |

### Geprüft, in Ordnung (mit Bedingungen)
| Teil | Einschätzung |
|---|---|
| Radiosender abspielen (direkt) | 🟢 Wie jedes Internetradio-Verzeichnis. Die Sender zahlen ihre Lizenzen selbst. |
| Hit-Mix in der App | 🟢 Die App schaltet nur zwischen Live-Sendern um (wie Zappen). Nichts wird gespeichert oder verändert. |
| Werbung stumm schalten | 🟢 Werbeblocker sind in Deutschland erlaubt (BGH, Adblock-Urteile). Es wird nur leiser gestellt, nichts entfernt. |
| Jamendo | 🟢 Creative-Commons-Musik über die offizielle API, nicht-kommerziell erlaubt (Hitradar ist kostenlos, ohne Werbung). Künstlername anzeigen. |
| Audius | 🟢 Künstler laden selbst hoch, Abspielen über die offizielle API ist erlaubt. Bei Beschwerden löscht Audius. |
| Deezer (Charts, Cover, 30-s-Vorschau) | 🟡 Über die öffentliche API erlaubt, Bedingungen: nicht-kommerziell, Deezer als Quelle nennen, Vorschau nicht speichern. Wird so gemacht. Falls Deezer die API sperrt: Cover über MusicBrainz/Cover Art Archive. |
| Podcasts, LibriVox | 🟢 Öffentliche Feeds bzw. gemeinfrei. |
| Senderlogos, Sendernamen | 🟢 Aus radio-browser.info, nur zur Erkennung. Auf Wunsch eines Senders entfernen (Sperrliste `blocklist.txt` gibt es schon). |
| Charts/„Beliebt“/„Mehr wie dieses“ | 🟢 Nur Zählungen und Titel = Fakten. |
| KI „Wünsch dir was“ | 🟢 Qwen2.5-1.5B unter Apache 2.0, läuft auf dem eigenen Server. |
| Bibliotheken | 🟢 Alle Apache 2.0 (AndroidX, Media3, Kotlin, Coil, Guava, aiohttp); Play-Dienste unter Googles SDK-Lizenz. |

### Noch offen / Entscheidung
- **Ausländische Sender**: Gegen TuneIn haben Plattenfirmen in Großbritannien gewonnen, weil TuneIn Sender verlinkt hat,
  die nur für ihr eigenes Land lizenziert waren. Danach hat TuneIn solche Sender auch in Deutschland gesperrt.
  Hitradar zeigt standardmäßig weltweit ~50.000 Sender. Sicherer: Standard auf deutschsprachige Sender (DACH) und Weltweit
  nur, wenn man es selbst einstellt. **Umgesetzt 27.11.:** Standard ist jetzt DACH, Weltweit lässt sich in den Einstellungen wählen (Kevin kann es zurückdrehen).
- **Name „Hitradar“** vor einer größeren Veröffentlichung einmal im Markenregister (DPMA, kostenlos online) prüfen.
- **Impressum/Kontakt**: Für Apps im Play Store ist eine Kontaktadresse Pflicht; bei Beschwerden schnell reagieren (Sender sperren, Eintrag löschen).
