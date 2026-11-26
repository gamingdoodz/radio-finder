"""
Radar-Server: beobachtet rund um die Uhr tausende Internetradios und weiß,
was gerade wo läuft. Die App fragt nur noch hier – Suche in unter einer Sekunde.

API:
  GET /health                -> {"ok": true, "stations": n, "live": n}
  GET /search?q=<text>       -> Sender, auf denen das gerade läuft
  GET /now?limit=<n>         -> alles, was gerade läuft (neueste zuerst)
"""
import asyncio
import base64
import gzip
import html
import json
import os
import re
import time
import unicodedata
from urllib.parse import urlparse

import aiohttp
from aiohttp import web

PORT = int(os.environ.get("PORT", "8080"))
MAX_STATIONS = int(os.environ.get("MAX_STATIONS", "12000"))
CONCURRENCY = int(os.environ.get("CONCURRENCY", "250"))

# ---------- Serverabsprache mit Valdrakor (Details: docs/SERVER-ABSPRACHE.md im Valdrakor-Repo) ----------
# Vorrang auf dem Server: Spiel vor Broker vor Hitradar vor Neuem. Das Spiel entscheidet.
# Stufe für Hitradar: budget.hitradar.level = frei | sparsam | pause
#   1. /api/last des Spiels  2. ohne Netz /run/serverabsprache/lage.json (gleiches Feld)
#   3. Datei fehlt oder älter als 2 Minuten: sparsam; nach 15 Minuten ohne Datei selbst messen
#      (Last je Kern ab 1,5 oder freier Speicher unter 10 % = pause, ab 1,0 oder unter 20 % = sparsam, sonst frei)
# frei = normal | sparsam = nichts Schweres neu anfangen, ein Drittel der Abfragen, keine KI, keine Cover
# pause = radar und KI fangen nichts Neues an; die App bekommt weiter den letzten Stand
# Meldung "jetzt": leicht (normal) | schwer (Senderliste laden, bis max. 6 h) | ruhe (bei pause, kaum Bedarf)
#   | update (nur radar-update/absprache.py: vor Neustart, bis max. 1 h, erst bei lage.json updateErlaubt true,
#     nie wenn updateDran "spiel"; danach sofort leicht). Ein laufendes "update" wird hier nie überschrieben.
# Die systemd-Grenzen des Spiels (radar.service.d/60-sparsam.conf) werden hier nie verändert.
LAGE_FILE = os.environ.get("ABSPRACHE_LAGE", "/run/serverabsprache/lage.json")
DIENST_FILE = os.environ.get("ABSPRACHE_DIENST", "/var/lib/serverabsprache/dienste/hitradar.json")
GAME_LAST_URL = os.environ.get("GAME_LAST_URL", "http://127.0.0.1:8100/api/last")
SPAR = {"low": False, "pause": False, "modus": "frei", "quelle": "start", "stufe": 0, "since": 0}
MELDUNG = {"jetzt": "leicht", "bis": 0, "am": 0}


def _level(d):
    """budget.hitradar.level aus der Antwort des Spiels (oder None)."""
    try:
        v = d["budget"]["hitradar"]["level"]
        v = str(v).strip().lower()
        return v if v in ("frei", "sparsam", "pause") else None
    except Exception:
        return None


def _selbst_messen() -> str:
    try:
        last = os.getloadavg()[0] / (os.cpu_count() or 1)
        mem = {}
        for line in open("/proc/meminfo"):
            k, v = line.split(":", 1)
            mem[k] = int(v.split()[0])
        frei = mem.get("MemAvailable", 0) / max(1, mem.get("MemTotal", 1))
    except Exception:
        return "sparsam"
    if last >= 1.5 or frei < 0.10:
        return "pause"
    if last >= 1.0 or frei < 0.20:
        return "sparsam"
    return "frei"


UNITS = ("radar.service", "llm.service")
CPU = {"usec": 0, "t": 0.0, "pct": 0.0}


def _cg(unit, name):
    return open(f"/sys/fs/cgroup/system.slice/{unit}/{name}").read()


def _ram_mb() -> int:
    """Ehrlich: was radar.py und die eigene KI (llm.service) gerade wirklich belegen (ohne frei gebbaren
    Datei-Zwischenspeicher), aufgerundet auf 100 MB."""
    total = 0
    for unit in UNITS:
        try:
            cur = int(_cg(unit, "memory.current").strip())
            stat = dict(l.split() for l in _cg(unit, "memory.stat").splitlines())
            total += max(0, cur - int(stat.get("inactive_file", 0)))
        except Exception:
            pass
    if total <= 0:
        try:
            import resource
            total = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
        except Exception:
            total = 1200 * 1024 * 1024
    mb = total / (1024 * 1024)
    return int(-(-mb // 100) * 100)


def _cpu_messen():
    """Mittlere CPU von radar und KI in Prozent eines Kerns (gleitender Mittelwert, alle 30 s gemessen)."""
    usec = 0
    for unit in UNITS:
        try:
            for l in _cg(unit, "cpu.stat").splitlines():
                if l.startswith("usage_usec"):
                    usec += int(l.split()[1])
        except Exception:
            pass
    t = time.time()
    if CPU["t"] and usec >= CPU["usec"]:
        pct = (usec - CPU["usec"]) / 1e4 / max(1.0, t - CPU["t"])
        CPU["pct"] = pct if CPU["pct"] == 0 else 0.9 * CPU["pct"] + 0.1 * pct
    CPU.update(usec=usec, t=t)


def _cpu_pct() -> int:
    """Gemessene CPU aufgerundet auf 10 %, mindestens 10; vor der ersten Messung 50."""
    if not CPU["pct"]:
        return 50
    return max(10, int(-(-CPU["pct"] // 10) * 10))


def melden(jetzt: str = "leicht", bis_s: int = 0):
    """Anmeldung/Meldung in /var/lib/serverabsprache/dienste/hitradar.json (beim Start, täglich, vor/nach schwerer Arbeit)."""
    now_ms = int(time.time() * 1000)
    # Ein laufendes Update (eingetragen von radar-update / absprache.py) nie überschreiben
    if jetzt != "update":
        try:
            with open(DIENST_FILE, encoding="utf-8") as h:
                alt = json.load(h)
            if alt.get("jetzt") == "update" and int(alt.get("bis") or 0) > now_ms:
                MELDUNG.update(jetzt="update", bis=int(alt["bis"]), am=time.time())
                return
        except Exception:
            pass
    bis = 0
    if jetzt == "schwer":
        bis = now_ms + min(max(bis_s, 60), 6 * 3600) * 1000     # höchstens 6 Stunden
    elif jetzt == "update":
        bis = now_ms + min(max(bis_s, 60), 3600) * 1000         # höchstens 1 Stunde
    info = {"prio": "hitradar", "was": "Radio und KI", "jetzt": jetzt, "bis": bis,
            "cpu": _cpu_pct(), "ramMb": _ram_mb(), "t": now_ms}
    try:
        os.makedirs(os.path.dirname(DIENST_FILE), exist_ok=True)
        tmp = DIENST_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as h:
            json.dump(info, h)
        os.chmod(tmp, 0o644)
        os.replace(tmp, DIENST_FILE)
        MELDUNG.update(jetzt=jetzt, bis=bis, am=time.time())
        print(f"Serverabsprache: gemeldet {jetzt} ({info['ramMb']} MB)", flush=True)
    except Exception as e:
        print("Serverabsprache: Meldung nicht möglich:", e, flush=True)
    # Alte Anmeldung an falscher Stelle (bis 26.11.) entfernen
    try:
        os.remove(os.path.join(os.path.dirname(os.path.dirname(DIENST_FILE)), "hitradar.json"))
    except Exception:
        pass


def darf_schwer() -> bool:
    return SPAR["modus"] == "frei"


def _setze(modus: str, quelle: str):
    if modus != SPAR["modus"]:
        SPAR["since"] = int(time.time())
        print(f"Serverabsprache: Hitradar -> {modus} ({quelle})", flush=True)
    SPAR.update(modus=modus, quelle=quelle, low=modus != "frei", pause=modus == "pause")


async def follow_game():
    melden("leicht")
    ohne_datei_seit = None
    async with aiohttp.ClientSession() as s:
        while True:
            modus, quelle = None, ""
            try:
                async with s.get(GAME_LAST_URL, timeout=aiohttp.ClientTimeout(total=4)) as r:
                    d = await r.json(content_type=None)
                SPAR["stufe"] = int(d.get("stufe") or 0)
                modus, quelle = _level(d), "api/last"
                if modus is None and d.get("hitradar") in ("low", "normal"):
                    modus = "sparsam" if d.get("hitradar") == "low" else "frei"   # ältere Spiel-Version
            except Exception:
                modus = None
            if modus is None:
                try:
                    if time.time() - os.path.getmtime(LAGE_FILE) < 120:
                        with open(LAGE_FILE, encoding="utf-8") as h:
                            modus, quelle = _level(json.load(h)), "lage.json"
                except Exception:
                    modus = None
            if modus is None:
                ohne_datei_seit = ohne_datei_seit or time.time()
                if time.time() - ohne_datei_seit >= 15 * 60:
                    modus, quelle = _selbst_messen(), "selbst gemessen"
                else:
                    modus, quelle = "sparsam", "keine Lage"
            else:
                ohne_datei_seit = None
            _setze(modus, quelle)
            _cpu_messen()
            # Bei "pause" braucht Hitradar kaum etwas: "ruhe", dann bekommen die anderen die Leistung
            soll = "ruhe" if modus == "pause" else "leicht"
            if MELDUNG["jetzt"] == "update":      # Update vorbei? Dann gilt wieder, was in der Datei steht
                try:
                    with open(DIENST_FILE, encoding="utf-8") as h:
                        alt = json.load(h)
                    if alt.get("jetzt") != "update":
                        MELDUNG.update(jetzt=alt.get("jetzt") or "leicht", bis=int(alt.get("bis") or 0))
                except Exception:
                    pass
            jetzt_ms = time.time() * 1000
            abgelaufen = MELDUNG["jetzt"] in ("schwer", "update") and MELDUNG["bis"] < jetzt_ms
            ruhig = MELDUNG["jetzt"] in ("leicht", "ruhe")
            # Stündlich neu melden (gemessene cpu/ramMb), bei Wechsel leicht <-> ruhe, abgelaufenes schwer/update
            if abgelaufen or (ruhig and (time.time() - MELDUNG["am"] > 3600 or MELDUNG["jetzt"] != soll)):
                melden(soll)
            await asyncio.sleep(30)
FRESH_SECONDS = 6 * 60
UA = "RadarServer/1.0"
RB_SERVERS = ["de1.api.radio-browser.info", "fi1.api.radio-browser.info", "de2.api.radio-browser.info"]

stations: list[dict] = []
now: dict[str, dict] = {}          # station id -> {title, norm, since, time}
no_status: set[str] = set()        # Server ohne Icecast-Statusseite
last_round = {"seconds": 0, "at": 0}


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^\w]+", " ", s).strip()


# Werbung/Ansagen, die wie "Künstler - Titel" aussehen ("Ein bisschen Verbraucherinformationen - paypal.com")
JUNK = re.compile(r"(?i)verbraucherinfo|ein angebot de[rs]|livestream|werbung|anzeige|hotline|jetzt anrufen|www\.|"
                  r"\b[\w-]+\.(?:com|de|ch|at|fm|net|org|info|eu|io|radio)\b|\d{3,}[\s/-]\d{2,}[\s/-]?\d*")


def is_song(t: str) -> bool:
    return (" - " in t or " – " in t) and not JUNK.search(t)


def put(st: dict, title: str):
    title = html.unescape(title).strip()
    t = time.time()
    old = now.get(st["id"])
    # exact = Liedanfang wirklich beobachtet (Wechsel zwischen zwei Abfragen). Sonst unbekannt (lief schon vorher).
    if old and old["title"] == title and t - old["time"] < FRESH_SECONDS:
        since, exact = old["since"], old.get("exact", False)
    elif old and t - old["time"] < 120:
        since, exact = (old["time"] + t) / 2, True     # Mitte zwischen den zwei Abfragen ist die beste Schätzung
    else:
        since, exact = t, False
    norm = normalize(title)
    now[st["id"]] = {"title": title, "norm": norm, "since": since, "time": t, "exact": exact}
    # Liedlänge im Hintergrund holen (für die genaue Prüfung am Liedende)
    if since != (old or {}).get("since") and is_song(title) and (norm not in covers or (covers[norm] and "dur" not in covers[norm])) and len(cover_queue) < 5000:
        cover_queue[norm] = title
    # Neues Lied auf einem Sender aus DE/AT/CH: für die Radio-Charts zählen
    if (not old or old["title"] != title) and is_song(title) and not TALK.search(title):
        if st.get("cc") in ("DE", "AT", "CH"):
            count_play(norm, title, st["id"], t)
        learn_neighbors(st["id"], norm, title)


# ---------------- Ähnliche Lieder: was Sender oft direkt hintereinander spielen ----------------
SIMILAR_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "similar.json")
last_song: dict = {}     # Sender -> zuletzt gespieltes Lied (norm)
neighbors: dict = {}     # norm -> {norm: Anzahl}
song_titles: dict = {}   # norm -> Titel


def learn_neighbors(sid, norm, title):
    song_titles[norm] = title
    prev = last_song.get(sid)
    last_song[sid] = norm
    if not prev or prev == norm:
        return
    for a, b in ((prev, norm), (norm, prev)):
        d = neighbors.setdefault(a, {})
        d[b] = d.get(b, 0) + 1
        if len(d) > 60:  # nur die häufigsten Nachbarn behalten
            for k, _ in sorted(d.items(), key=lambda kv: kv[1])[:20]:
                del d[k]
    if len(neighbors) > 400_000:  # Speicher begrenzen: seltene Lieder vergessen
        for k in [k for k, d in neighbors.items() if sum(d.values()) < 3]:
            neighbors.pop(k, None)


def save_similar():
    try:
        keep = {k: dict(sorted(d.items(), key=lambda kv: -kv[1])[:25]) for k, d in neighbors.items() if sum(d.values()) >= 2}
        titles = {k: song_titles[k] for k in set(keep) | {n for d in keep.values() for n in d} if k in song_titles}
        with open(SIMILAR_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump({"n": keep, "t": titles}, f)
        os.replace(SIMILAR_FILE + ".tmp", SIMILAR_FILE)
    except Exception as e:
        print("Ähnliche speichern:", e, flush=True)


def load_similar():
    try:
        with open(SIMILAR_FILE, encoding="utf-8") as f:
            d = json.load(f)
        neighbors.update(d.get("n", {}))
        song_titles.update(d.get("t", {}))
        print(f"Ähnliche Lieder geladen: {len(neighbors)}", flush=True)
    except Exception:
        pass


_similar_cache: dict = {}


async def h_similar(req):
    q = normalize(req.query.get("q", ""))
    if not q:
        return web.json_response([])
    hit = _similar_cache.get(q)
    if hit and time.time() - hit[0] < 600:
        return gz(web.json_response(hit[1]))
    key = q if q in neighbors else None
    if key is None:
        # Ungefähr passend: Lied, das alle Wörter enthält (das mit den meisten Daten)
        words = q.split()
        best = 0
        # höchstens 150.000 Lieder durchsehen, damit der Server nicht blockiert
        for i, (k, d) in enumerate(neighbors.items()):
            if i > 150_000:
                break
            if all(w in k for w in words):
                n = sum(d.values())
                if n > best:
                    best, key = n, k
    out = []
    if key:
        for k, n in sorted(neighbors[key].items(), key=lambda kv: -kv[1])[:30]:
            if n >= 2 and k in song_titles:
                out.append({"title": song_titles[k], "score": n})
    if len(_similar_cache) > 3000:
        _similar_cache.clear()
    _similar_cache[q] = (time.time(), out)
    return gz(web.json_response(out))


SHARED: dict = {}  # gemeinsam genutzte Verbindung (vom Scanner)


# ---------------- Cover für alle: einmal bei Deezer holen, für alle Nutzer merken ----------------
cover_queue: dict = {}   # norm -> Titel, dessen Länge/Cover noch geholt werden soll


async def cover_worker():
    """Holt Liedlängen nach und nach (Deezer erlaubt nur wenige Anfragen pro Sekunde)."""
    while True:
        await asyncio.sleep(5 if SPAR["low"] else 0.4)
        if SPAR["pause"] or not cover_queue or "session" not in SHARED:
            continue
        norm, title = next(iter(cover_queue.items()))
        cover_queue.pop(norm, None)
        if norm in covers and (not covers[norm] or "dur" in covers[norm]):
            continue
        try:
            await find_cover(title)
        except Exception:
            pass


COVERS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "covers.json")
covers: dict = {}          # norm -> {"art","title","artist"} oder {} (= nichts gefunden)
_cover_sem = asyncio.Semaphore(4)


def load_covers():
    try:
        with open(COVERS_FILE, encoding="utf-8") as f:
            covers.update(json.load(f))
    except Exception:
        pass


def save_covers():
    try:
        with open(COVERS_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(covers, f)
        os.replace(COVERS_FILE + ".tmp", COVERS_FILE)
    except Exception:
        pass


async def find_cover(title: str):
    key = normalize(title)
    if key in covers and (not covers[key] or "dur" in covers[key]):
        return covers[key]
    artist = re.split(r" - | – ", title, 1)[0].strip()
    song = re.split(r" - | – ", title, 1)[-1]
    song = re.sub(r"\(.*?\)|\[.*?\]", "", song).strip()
    res = {}
    try:
        # Erst genau, dann nur mit dem Hauptkünstler (z. B. "feat. Seth McFarland" falsch geschrieben)
        main_artist = re.split(r"(?i)\s+(?:feat\.?|ft\.?|featuring|x|&|und|and|vs\.?)\s+|,", artist)[0].strip()
        data = {}
        for q in dict.fromkeys([f"{artist} {song}", f"{main_artist} {song}"]):
            async with _cover_sem:
                async with SHARED["session"].get("https://api.deezer.com/search", params={"q": q, "limit": "5"},
                                                 timeout=aiohttp.ClientTimeout(total=8)) as r:
                    data = await r.json(content_type=None)
            if data.get("data"):
                break
        words_t = [w for w in normalize(song).split() if len(w) >= 2]
        words_a = [w for w in normalize(artist).split() if len(w) >= 3]
        for o in data.get("data", []):
            t, a = normalize(o.get("title_short") or o.get("title") or ""), normalize((o.get("artist") or {}).get("name", ""))
            if words_t and all(w in key for w in normalize(t).split() if len(w) >= 2) and (not words_a or any(w in a for w in words_a)):
                art = (o.get("album") or {}).get("cover_big") or ""
                if art:
                    res = {"art": art, "title": o.get("title_short") or o.get("title"), "artist": (o.get("artist") or {}).get("name", ""),
                           "dur": int(o.get("duration") or 0)}
                    break
    except Exception:
        return None  # später nochmal versuchen
    if len(covers) > 300_000:
        covers.clear()
    covers[key] = res
    return res


async def h_cover(req):
    t = (req.query.get("q") or "").strip()[:200]
    if not is_song(t):
        return web.json_response({})
    res = await find_cover(t)
    return web.json_response(res or {})


# ---------------- Prüfen auf Zuruf: läuft das wirklich gerade? ----------------
async def h_verify(req):
    ids = [i for i in (req.query.get("ids") or "").split(",") if i][:8]
    m = ids_map()
    sts = [m[i] for i in ids if i in m]

    async def one(st):
        try:
            await asyncio.wait_for(check(SHARED["session"], st), timeout=7)
        except Exception:
            pass
    await asyncio.gather(*(one(st) for st in sts))
    t = time.time()
    out = {}
    for st in sts:
        n = now.get(st["id"])
        if n and t - n["time"] < 15:
            out[st["id"]] = {"title": n["title"], "since": int(n["since"]), "exact": bool(n.get("exact"))}
    return web.json_response(out)


# ---------------- Senderlisten: beliebt, Kinder, Genres ----------------
_lists_cache: dict = {}


async def h_list(req):
    typ = (req.query.get("t") or "popular").lower()[:40]
    hit = _lists_cache.get(typ)
    if hit and time.time() - hit[0] < 3600:
        return gz(web.json_response(hit[1]))
    # Liste ist nach Beliebtheit sortiert
    if typ == "popular":
        sel = [st for st in stations if st.get("cc") in ("DE", "AT", "CH")][:150]
    elif typ == "kids":
        rx = re.compile(r"kinder|kids|children|teddy|kiraka|toggo|kakadu")
        sel = [st for st in stations if rx.search(st.get("tags", "")) or rx.search(st["name"].lower())][:60]
    elif typ.startswith("tag:"):
        tag = typ[4:]
        de = [st for st in stations if tag in st.get("tags", "") and st.get("cc") == "DE"][:30]
        other = [st for st in stations if tag in st.get("tags", "") and st.get("cc") != "DE"][:40]
        sel = de + other
    else:
        sel = []
    out = [{"id": st["id"], "name": st["name"], "url": st["url"], "favicon": st["favicon"]} for st in sel]
    _lists_cache[typ] = (time.time(), out)
    return gz(web.json_response(out))


# ---------------- Gemeinsam lernen: was Hitradar-Hörer mögen (anonym) ----------------
POPULAR_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "popular.json")
pop_days: dict = {}   # Tag -> {"songs": {norm: n}, "mixes": {key: n}}
pop_titles: dict = {}  # norm -> Titel
_pop_cache = {"t": 0, "body": None}
EVENT_WEIGHT = {"heard": 1, "fav": 3, "playlist": 3, "mix": 1}


def load_popular():
    try:
        with open(POPULAR_FILE, encoding="utf-8") as f:
            d = json.load(f)
        pop_days.update({int(k): v for k, v in d.get("days", {}).items()})
        pop_titles.update(d.get("titles", {}))
    except Exception:
        pass


def save_popular():
    try:
        with open(POPULAR_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump({"days": pop_days, "titles": pop_titles}, f)
        os.replace(POPULAR_FILE + ".tmp", POPULAR_FILE)
    except Exception:
        pass


async def h_event(req):
    """Anonyme Meldung aus der App: Lied gehört/gemerkt oder Mix gestartet. Keine Nutzerdaten."""
    typ = req.query.get("type", "")
    val = (req.query.get("v") or "").strip()[:150]
    if typ not in EVENT_WEIGHT or not val:
        return web.json_response({"ok": False})
    day = int(time.time() // 86400)
    d = pop_days.setdefault(day, {"songs": {}, "mixes": {}})
    if typ == "mix":
        if not re.fullmatch(r"[a-z0-9:_\- ]{1,60}", val.lower()) or val.startswith(("artist:", "similar:", "my:")):
            return web.json_response({"ok": False})
        d["mixes"][val] = d["mixes"].get(val, 0) + 1
    else:
        if not is_song(val) or TALK.search(val):
            return web.json_response({"ok": False})
        k = normalize(val)
        d["songs"][k] = d["songs"].get(k, 0) + EVENT_WEIGHT[typ]
        pop_titles[k] = val
    for old in [k for k in pop_days if k < day - 7]:
        pop_days.pop(old, None)
    return web.json_response({"ok": True})


async def h_popular(req):
    if not _pop_cache["body"] or time.time() - _pop_cache["t"] > 300:
        songs: dict = {}
        mixes: dict = {}
        today = int(time.time() // 86400)
        for day, d in pop_days.items():
            w = 1.0 if day == today else 0.7 ** (today - day)  # neuere Tage zählen mehr
            for k, n in d["songs"].items():
                songs[k] = songs.get(k, 0) + n * w
            for k, n in d["mixes"].items():
                mixes[k] = mixes.get(k, 0) + n * w
        out = {
            "songs": [{"title": pop_titles.get(k, k), "score": round(v, 1)}
                      for k, v in sorted(songs.items(), key=lambda kv: -kv[1])[:120]
                      if v >= 2 and real_song(k, pop_titles.get(k, k))][:60],
            "mixes": [{"key": k, "score": round(v, 1)} for k, v in sorted(mixes.items(), key=lambda kv: -kv[1])[:20]],
        }
        _pop_cache.update(t=time.time(), body=gzip.compress(json.dumps(out, ensure_ascii=False).encode(), 6))
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=_pop_cache["body"], content_type="application/json", headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(_pop_cache["body"]), content_type="application/json")


# ---------------- Sender nach Namen suchen (alle ~50.000) ----------------
_station_names = {"n": -1, "list": []}


async def h_stations(req):
    words = [w for w in normalize(req.query.get("q", "")).split() if w]
    if not words:
        return web.json_response([])
    if _station_names["n"] != len(stations):
        _station_names.update(n=len(stations), list=[(normalize(st["name"]), st) for st in stations])
    out = []
    # Liste ist nach Beliebtheit sortiert: die ersten Treffer sind die bekanntesten Sender
    for name, st in _station_names["list"]:
        if all(w in name for w in words):
            n = now.get(st["id"])
            out.append({"id": st["id"], "name": st["name"], "url": st["url"], "favicon": st["favicon"],
                        "title": n["title"] if n and time.time() - n["time"] < FRESH_SECONDS else ""})
            if len(out) >= 30:
                break
    return gz(web.json_response(out))


# ---------------- Werbung am Senderanfang: von allen Apps gemeldet, für alle gemerkt ----------------
ADS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ads.json")
ads: dict = {}   # Sender-ID -> [Durchschnitt ms, Anzahl Meldungen]
_ads_cache = {"t": 0, "body": None}


def load_ads():
    try:
        with open(ADS_FILE, encoding="utf-8") as f:
            ads.update(json.load(f))
    except Exception:
        pass


def save_ads():
    try:
        with open(ADS_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(ads, f)
        os.replace(ADS_FILE + ".tmp", ADS_FILE)
    except Exception:
        pass


async def h_ad(req):
    """Eine App meldet: bei Sender s kam ms Millisekunden Werbung (0 = keine)."""
    sid = (req.query.get("s") or "")[:64]
    try:
        ms = int(req.query.get("ms", "-1"))
    except ValueError:
        ms = -1
    if not sid or ms < 0 or ms > 180_000 or sid not in ids_map():
        return web.json_response({"ok": False})
    avg, n = ads.get(sid, [0, 0])
    n = min(n + 1, 20)  # neuere Meldungen zählen stärker
    avg = avg + (ms - avg) / n
    ads[sid] = [int(avg), n]
    return web.json_response({"ok": True})


async def h_ads(req):
    """Alle Sender mit bekannter Vorab-Werbung (für die Apps)."""
    if not _ads_cache["body"] or time.time() - _ads_cache["t"] > 300:
        out = {sid: v[0] for sid, v in ads.items() if v[0] >= 5000}
        _ads_cache.update(t=time.time(), body=gzip.compress(json.dumps(out).encode(), 6))
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=_ads_cache["body"], content_type="application/json", headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(_ads_cache["body"]), content_type="application/json")


# ---------------- Wünsch dir was: kleine KI auf dem Server ----------------
GENRES = {
    "xmas": "Weihnachtslieder", "charts": "aktuelle Hits/Charts", "pop": "Pop und Deutsch-Pop", "rap": "Rap und Hip-Hop",
    "dance": "Dance, Electronic, House, Techno", "rock": "Rock und Alternative", "schlager": "Schlager",
    "party": "Party, Après-Ski, Ballermann, Fasching", "80s": "80er", "90s": "90er", "2000s": "2000er",
    "summer": "Sommerhits, gute Laune", "deutschrap": "Deutschrap", "rockclassics": "Rock-Klassiker, Metal",
    "oldies": "Oldies, 60er, 70er", "latin": "Latin, Reggaeton", "country": "Country", "jazz": "Jazz, Soul, Blues",
    "classic": "Klassik, Orchester", "chill": "ruhig, entspannt, Einschlafen, Lounge, Romantik", "kids": "Kinderlieder",
}
LLM_URL = "http://127.0.0.1:8090/completion"
_wish_cache: dict = {}
_llm_lock = asyncio.Semaphore(1)
WISH_RULES = [
    ("xmas", r"weihnacht|christmas|xmas|advent"), ("kids", r"kinder|kids"), ("chill", r"ruhig|entspann|chill|schlaf|lounge|romant|kuschel"),
    ("party", r"party|feier|ballermann|apres|après|fasching|karneval"), ("80s", r"80er|achtziger"), ("90s", r"90er|neunziger"),
    ("2000s", r"2000er|nuller"), ("oldies", r"oldie|60er|70er|siebziger|sechziger"), ("deutschrap", r"deutschrap|deutscher rap"),
    ("rap", r"rap|hip ?hop"), ("rock", r"rock|metal|gitarre"), ("schlager", r"schlager"), ("dance", r"dance|techno|house|elektro|edm|tanz"),
    ("summer", r"sommer|gute laune|fröhlich|sonne"), ("jazz", r"jazz|soul|blues"), ("classic", r"klassik|orchester|mozart|beethoven"),
    ("latin", r"latin|reggaeton|salsa"), ("country", r"country"), ("charts", r"hits|charts|aktuell|neu"),
]


def wish_rules(q: str):
    ql = q.lower()
    for key, rx in WISH_RULES:
        if re.search(rx, ql):
            return {"genre": key, "artist": None, "ai": False}
    return None


async def ask_llm(session, q: str):
    genres = "\n".join(f"- {k}: {v}" for k, v in GENRES.items())
    # Fester Anfang (wird von der KI zwischengespeichert), der Wunsch kommt erst am Ende -> schnelle Antworten
    prompt = (
        "<|im_start|>system\nDu wählst Musik für eine Radio-App. Antworte nur mit JSON.\n"
        f"Mögliche genre-Werte:\n{genres}\n"
        "Wenn ein bestimmter Künstler oder eine Band genannt wird, schreibe den Namen in artist, sonst einen leeren Text.<|im_end|>\n"
        f"<|im_start|>user\nMusikwunsch: \"{q}\"<|im_end|>\n"
        "<|im_start|>assistant\n"
    )
    schema = {"type": "object", "properties": {
        "genre": {"type": "string", "enum": list(GENRES)},
        "artist": {"type": "string", "maxLength": 40}}, "required": ["genre", "artist"]}
    if SPAR["low"]:
        raise RuntimeError("Spiel braucht die Leistung")   # Stichwort-Regeln antworten stattdessen
    async with _llm_lock:
        async with session.post(LLM_URL, json={"prompt": prompt, "n_predict": 32, "temperature": 0.1,
                                               "json_schema": schema, "cache_prompt": True},
                                timeout=aiohttp.ClientTimeout(total=20)) as r:
            data = await r.json()
    out = json.loads(data.get("content", "{}"))
    return {"genre": out.get("genre") if out.get("genre") in GENRES else None,
            "artist": (out.get("artist") or "").strip() or None, "ai": True}


async def h_wish(req):
    q = (req.query.get("q") or "").strip()[:200]
    if not q:
        return web.json_response({})
    key = q.lower()
    hit = _wish_cache.get(key)
    if hit:
        return web.json_response(hit)
    # Eindeutige Stichwörter ("ruhig", "Party", "90er" …) sofort beantworten, die KI nur für den Rest
    res = wish_rules(q)
    if res is None:
        try:
            async with aiohttp.ClientSession() as session:
                res = await ask_llm(session, q)
        except Exception:
            res = None
    # Künstler nur übernehmen, wenn er wirklich im Wunsch vorkommt (die KI erfindet sonst gern einen)
    if res and res.get("artist"):
        a = res["artist"].lower()
        if a not in q.lower() or len(a) > 0.8 * len(q):
            res["artist"] = None
    if not res or not (res.get("genre") or res.get("artist")):
        res = wish_rules(q) or {"genre": None, "artist": None, "ai": False}
    if len(_wish_cache) > 2000:
        _wish_cache.clear()
    _wish_cache[key] = res
    return web.json_response(res)


# ---------------- Radio-Charts: meistgespielt in den letzten 24 Stunden ----------------
TALK = re.compile(r"(?i)nachrichten|news|verkehr|wetter|werbung|podcast|interview|sendung|moderation|gewinnspiel|jingle|station id|\bon air\b")
CHARTS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "charts.json")
chart_hours: dict = {}     # Stunde -> {norm: Anzahl Sender}
chart_seen: dict = {}      # Stunde -> set((norm, sender)) – jeder Sender zählt pro Stunde nur einmal
chart_titles: dict = {}    # norm -> schönster Titel
_charts_cache = {"t": 0, "body": None}


def count_play(norm, title, sid, t):
    hour = int(t // 3600)
    seen = chart_seen.setdefault(hour, set())
    if (norm, sid) in seen:
        return
    seen.add((norm, sid))
    c = chart_hours.setdefault(hour, {})
    c[norm] = c.get(norm, 0) + 1
    chart_titles[norm] = title
    for h in [h for h in chart_hours if h < hour - 24]:
        chart_hours.pop(h, None)
        chart_seen.pop(h, None)


def save_charts():
    try:
        hours = {str(h): dict(sorted(c.items(), key=lambda kv: -kv[1])[:3000]) for h, c in chart_hours.items()}
        keep = {k for c in hours.values() for k in c}
        with open(CHARTS_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump({"hours": hours, "titles": {k: v for k, v in chart_titles.items() if k in keep}}, f)
        os.replace(CHARTS_FILE + ".tmp", CHARTS_FILE)
    except Exception as e:
        print("Charts speichern:", e, flush=True)


def load_charts():
    try:
        with open(CHARTS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        now_h = int(time.time() // 3600)
        for h, c in d.get("hours", {}).items():
            if int(h) >= now_h - 24:
                chart_hours[int(h)] = c
        chart_titles.update(d.get("titles", {}))
        print(f"Charts geladen: {len(chart_hours)} Stunden", flush=True)
    except Exception:
        pass


async def h_charts(req):
    # Alle 5 Minuten neu berechnen, sonst fertige Antwort
    if not _charts_cache["body"] or time.time() - _charts_cache["t"] > 300:
        total: dict = {}
        now_h = int(time.time() // 3600)
        for h, c in chart_hours.items():
            if h >= now_h - 24:
                for k, n in c.items():
                    total[k] = total.get(k, 0) + n
        top = sorted(((k, n) for k, n in total.items() if is_song(chart_titles.get(k, k))), key=lambda kv: -kv[1])[:160]
        # Nur echte Lieder (keine Sender-Slogans/Sendungen); was noch nicht nachgeschlagen ist, kommt beim nächsten Mal
        top = [(k, n) for k, n in top if real_song(k, chart_titles.get(k, k))][:100]
        out = [{"title": chart_titles.get(k, k), "plays": n} for k, n in top if n >= 3]
        _charts_cache.update(t=time.time(), body=gzip.compress(json.dumps(out, ensure_ascii=False).encode(), 6))
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=_charts_cache["body"], content_type="application/json", headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(_charts_cache["body"]), content_type="application/json")


_rising_cache = {"t": 0, "body": None}


async def h_rising(req):
    """Aufsteiger: Lieder, die in den letzten 6 Stunden viel öfter laufen als davor (neu oder gerade angesagt)."""
    if not _rising_cache["body"] or time.time() - _rising_cache["t"] > 600:
        now_h = int(time.time() // 3600)
        recent: dict = {}
        before: dict = {}
        for h, c in chart_hours.items():
            target = recent if h > now_h - 6 else before if h > now_h - 30 else None
            if target is None:
                continue
            for k, n in c.items():
                target[k] = target.get(k, 0) + n
        scored = []
        for k, r in recent.items():
            if r < 6 or not is_song(chart_titles.get(k, k)) or not real_song(k, chart_titles.get(k, k)):
                continue
            b = before.get(k, 0) * 6 / 24
            if r > 1.6 * b + 2:
                scored.append((r - b, k))
        scored.sort(reverse=True)
        out = [{"title": chart_titles.get(k, k), "score": round(sc, 1)} for sc, k in scored[:60]]
        _rising_cache.update(t=time.time(), body=json.dumps(out, ensure_ascii=False))
    return web.Response(text=_rising_cache["body"], content_type="application/json")


# ---------------- Senderliste ----------------

STATIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stations_cache.json")


def station_cap() -> int:
    """MAX_STATIONS meistgehörte weltweit + bis zu 4.200 zusätzliche aus DE/AT/CH."""
    return MAX_STATIONS + 4200


def load_cached_stations() -> bool:
    """Beim Start sofort die zuletzt gespeicherte Senderliste nehmen (kein Warten auf radio-browser)."""
    global stations
    try:
        with open(STATIONS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        if data:
            # Höchstens so viele Sender wie erlaubt (MAX_STATIONS setzt das Spiel): DE/AT/CH zuerst behalten
            if len(data) > station_cap():
                dach = [s for s in data if s.get("cc") in ("DE", "AT", "CH")]
                rest = [s for s in data if s.get("cc") not in ("DE", "AT", "CH")]
                data = (dach + rest)[:station_cap()]
            stations = data
            print(f"Senderliste aus Speicher: {len(stations)} Sender", flush=True)
            return True
    except Exception:
        pass
    return False


async def load_stations(session: aiohttp.ClientSession):
    global stations
    # In Seiten zu je 10.000 holen – eine riesige Anfrage bricht gern ab
    queries = [f"limit=10000&offset={o}" for o in range(0, MAX_STATIONS, 10000)] + [
        "countrycode=DE&limit=3000",
        "countrycode=AT&limit=600",
        "countrycode=CH&limit=600",
    ]
    found: dict[str, dict] = {}
    for q in queries:
        for host in RB_SERVERS:
            try:
                url = f"https://{host}/json/stations/search?{q}&order=clickcount&reverse=true&hidebroken=true"
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=180)) as r:
                    data = await r.json(content_type=None)
                for o in data:
                    sid = o.get("stationuuid")
                    u = (o.get("url_resolved") or o.get("url") or "").strip()
                    if not sid or not u or o.get("hls") == 1:
                        continue
                    found.setdefault(sid, {
                        "id": sid,
                        "name": re.sub(r"\s+", " ", re.sub(r"[_#*|~=]+", " ", o.get("name") or "")).strip() or "Radio",
                        "url": u,
                        "favicon": (o.get("favicon") or "").strip(),
                        "cc": (o.get("countrycode") or "").upper(),
                        "tags": (o.get("tags") or "").lower()[:200],
                    })
                break
            except Exception:
                continue
    # Sperrliste (z. B. auf Wunsch eines Senders)
    try:
        block = [l.strip().lower() for l in open(os.path.join(os.path.dirname(__file__), "blocklist.txt"), encoding="utf-8")
                 if l.strip() and not l.startswith("#")]
    except Exception:
        block = []
    if block:
        found = {k: v for k, v in found.items()
                 if k.lower() not in block and not any("." in b and b in v["url"].lower() for b in block)}
    # Nur übernehmen, wenn die neue Liste nicht viel kleiner ist (sonst hat radio-browser gehakt)
    if found and (not stations or len(found) >= 0.8 * min(len(stations), station_cap())):
        stations = list(found.values())
        try:
            with open(STATIONS_FILE + ".tmp", "w", encoding="utf-8") as f:
                json.dump(stations, f)
            os.replace(STATIONS_FILE + ".tmp", STATIONS_FILE)
        except Exception as e:
            print("Senderliste speichern:", e, flush=True)
        print(f"Senderliste: {len(stations)} Sender", flush=True)
        return True
    print(f"Senderliste unvollständig ({len(found)}), behalte {len(stations)} Sender", flush=True)
    return False


# ---------------- Titel abfragen ----------------

def server_key(u: str):
    try:
        p = urlparse(u)
        port = p.port or (443 if p.scheme == "https" else 80)
        return f"{p.scheme}://{p.hostname}:{port}"
    except Exception:
        return None


async def icecast_titles(session, key: str):
    try:
        async with session.get(f"{key}/status-json.xsl", timeout=aiohttp.ClientTimeout(total=4)) as r:
            if r.status != 200:
                return None
            data = json.loads(await r.content.read(1_000_000))
        src = data.get("icestats", {}).get("source")
        if src is None:
            return None
        if isinstance(src, dict):
            src = [src]
        out = {}
        for o in src:
            path = urlparse(o.get("listenurl") or "").path
            title = (o.get("title") or "").strip()
            artist = (o.get("artist") or "").strip()
            full = f"{artist} - {title}" if artist and artist.lower() not in title.lower() else title
            if full and full != "-":
                out[path] = full
        return out or None
    except Exception:
        return None


async def icy_title(session, u: str):
    try:
        async with session.get(u, headers={"Icy-MetaData": "1"},
                               timeout=aiohttp.ClientTimeout(total=8, sock_read=4)) as r:
            metaint = int(r.headers.get("icy-metaint", "0") or 0)
            if r.status != 200 or not (0 < metaint <= 128_000):
                return None
            for _ in range(2):
                await r.content.readexactly(metaint)
                n = (await r.content.readexactly(1))[0]
                if n == 0:
                    continue
                raw = await r.content.readexactly(n * 16)
                try:
                    text = raw.decode("utf-8")
                except UnicodeDecodeError:
                    text = raw.decode("latin-1")
                m = re.search(r"StreamTitle='(.*?)';", text, re.S)
                t = m.group(1).strip() if m else ""
                return t if t and t != "-" else None
    except Exception:
        return None
    return None


# Laufende Abfrage statt starrer Runden: Sender mit Liedtiteln werden alle ~75 s
# neu abgefragt, Sender ohne Titel immer seltener (bis 1x pro Stunde).
due: list = []                      # Heap: (nächster Zeitpunkt, Sender-ID)
fails: dict[str, int] = {}
status_cache: dict[str, tuple] = {}  # Server -> (Zeit, Titel je Pfad)
stats = {"checked": 0, "since": time.time()}


async def status_for(session, key):
    if not key or key in no_status:
        return None
    hit = status_cache.get(key)
    if hit and time.time() - hit[0] < 30:
        return hit[1]
    titles = await icecast_titles(session, key)
    if titles is None:
        no_status.add(key)
    status_cache[key] = (time.time(), titles)
    return titles


async def check(session, st):
    key = server_key(st["url"])
    titles = await status_for(session, key)
    t = None
    if titles:
        path = urlparse(st["url"]).path or "/"
        t = titles.get(path) or titles.get(path.rstrip("/"))
    if t is None:
        t = await icy_title(session, st["url"])
    stats["checked"] += 1
    sid = st["id"]
    if t:
        put(st, t)
        fails[sid] = 0
        # Liedlänge bekannt: genau dann nochmal schauen, wenn das Lied zu Ende sein müsste
        n = now.get(sid)
        if n and is_song(n["title"]):
            dur = (covers.get(n["norm"]) or {}).get("dur", 0)
            if dur:
                left = n["since"] + dur - time.time()
                if left > 0:
                    return max(8, min(left - 3, 90))
                if left > -150:
                    return 12      # müsste vorbei sein: kurz nacheinander prüfen, bis der Wechsel kommt
        return 60 + (hash(sid) % 30)
    f = fails.get(sid, 0) + 1
    fails[sid] = f
    return min(3600, 120 * 2 ** min(f, 5))


def schedule_all():
    import heapq
    due.clear()
    t = time.time()
    for i, st in enumerate(stations):
        heapq.heappush(due, (t + i * 0.002, st["id"]))


async def worker(session, ids):
    """Ein einziger Verteiler statt hunderter wartender Abfrager: schläft bis zum nächsten fälligen Sender
    und startet höchstens CONCURRENCY Abfragen gleichzeitig (sparsam: ein Drittel, pause: keine)."""
    import heapq
    running = {"n": 0}
    laufend = set()          # Verweise halten, sonst räumt Python laufende Abfragen weg
    frei = asyncio.Event()
    frei.set()

    async def one(st, sid):
        try:
            nxt = await check(session, st)
        except Exception:
            nxt = 600
        if SPAR["low"]:
            nxt *= 1.5
        heapq.heappush(due, (time.time() + nxt, sid))
        running["n"] -= 1
        frei.set()

    while True:
        if SPAR["pause"]:
            await asyncio.sleep(5)
            continue
        if not due:
            await asyncio.sleep(1)
            continue
        wait = due[0][0] - time.time()
        if wait > 0:
            await asyncio.sleep(min(wait, 2))
            continue
        grenze = max(1, CONCURRENCY // 3) if SPAR["low"] else CONCURRENCY
        if running["n"] >= grenze:
            frei.clear()
            try:
                await asyncio.wait_for(frei.wait(), 5)
            except asyncio.TimeoutError:
                pass
            continue
        _, sid = heapq.heappop(due)
        st = ids.get(sid)
        if st is None:
            continue
        running["n"] += 1
        t = asyncio.create_task(one(st, sid))
        laufend.add(t)
        t.add_done_callback(laufend.discard)


async def scanner():
    conn = aiohttp.TCPConnector(limit=CONCURRENCY, limit_per_host=20, ttl_dns_cache=600, ssl=False)
    async with aiohttp.ClientSession(connector=conn, headers={"User-Agent": UA}) as session:
        SHARED["session"] = session
        load_charts()
        load_similar()
        load_ads()
        load_covers()
        load_popular()
        load_traffic()
        asyncio.create_task(cover_worker())
        ids: dict = {}
        # Sofort mit der gespeicherten Liste loslegen, die frische Liste kommt danach
        workers = [asyncio.create_task(worker(session, ids))]
        if load_cached_stations():
            ids.update({s["id"]: s for s in stations})
            schedule_all()
        # Erst die Vorgabe des Spiels abwarten (höchstens 10 s)
        for _ in range(20):
            if SPAR["quelle"] != "start":
                break
            await asyncio.sleep(0.5)
        # Senderliste neu laden ist schwere Arbeit: nur bei "frei" (ohne gespeicherte Liste geht es nicht anders)
        if not stations or darf_schwer():
            melden("schwer", 30 * 60)
            try:
                if await load_stations(session):
                    ids.clear(); ids.update({s["id"]: s for s in stations})
                    schedule_all()
            finally:
                melden("leicht")
        while True:
            await asyncio.sleep(60)
            el = time.time() - stats["since"]
            last_round.update(seconds=int(el), at=int(time.time()))
            print(f"{len(stations)} Sender, {stats['checked']} Abfragen, live: {len(fresh())}", flush=True)
            update_traffic()
            if int(el) // 60 % 10 == 0:
                save_charts()
                save_ads()
                save_covers()
                save_popular()
            if int(el) // 60 % 30 == 15:
                save_similar()
            # Einmal am Tag neu – und öfter, solange die Liste zu klein ist
            # Einmal am Tag neu – und öfter, solange die Liste deutlich zu klein ist. Nur wenn das Spiel "frei" sagt.
            klein = len(stations) < 0.8 * min(20000, station_cap())
            if (el > 24 * 3600 or klein and int(el) // 60 % 30 == 29) and darf_schwer():
                melden("schwer", 30 * 60)
                try:
                    if await load_stations(session):
                        ids.clear(); ids.update({s["id"]: s for s in stations})
                        schedule_all()
                finally:
                    melden("leicht")
                stats.update(checked=0, since=time.time())


# ---------------- Casten: Sender ohne https weiterleiten (mit Datenbremse) ----------------
TRAFFIC_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "traffic.json")
RELAY_STOP = 8 * 1000 ** 4      # ab 8 TB im Monat keine Weiterleitung mehr (Oracle: 10 TB frei)
RELAY_MAX = 400                 # gleichzeitige Weiterleitungen
traffic = {"month": "", "bytes": 0, "last": 0}
relays = {"n": 0}


def tx_bytes() -> int:
    """Gesendete Bytes des ganzen Servers (alle Netzwerkkarten außer lo) – zählt auch andere Dienste mit."""
    total = 0
    try:
        for dev in os.listdir("/sys/class/net"):
            if dev == "lo":
                continue
            with open(f"/sys/class/net/{dev}/statistics/tx_bytes") as f:
                total += int(f.read().strip())
    except Exception:
        pass
    return total


def load_traffic():
    try:
        with open(TRAFFIC_FILE, encoding="utf-8") as f:
            traffic.update(json.load(f))
    except Exception:
        pass
    traffic["last"] = tx_bytes()   # Zähler startet nach Neustart des Rechners bei 0


def update_traffic():
    month = time.strftime("%Y-%m")
    cur = tx_bytes()
    delta = cur - traffic["last"] if cur >= traffic["last"] else cur
    traffic["last"] = cur
    if traffic["month"] != month:
        traffic.update(month=month, bytes=0)
    traffic["bytes"] += max(0, delta)
    try:
        with open(TRAFFIC_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(traffic, f)
        os.replace(TRAFFIC_FILE + ".tmp", TRAFFIC_FILE)
    except Exception:
        pass


def relay_ok() -> bool:
    return traffic["bytes"] < RELAY_STOP and relays["n"] < RELAY_MAX


async def h_relay(req):
    """Reicht einen Senderstream unverändert durch (nur bekannte Sender, nichts wird gespeichert)."""
    st = ids_map().get(req.query.get("id") or "")
    if not st:
        return web.Response(status=404)
    if not relay_ok():
        return web.Response(status=503, text="Weiterleitung pausiert")
    relays["n"] += 1
    resp = None
    try:
        # Eigene Verbindungen, damit Weiterleitungen die Sendersuche nicht ausbremsen
        if "relay" not in SHARED:
            SHARED["relay"] = aiohttp.ClientSession(connector=aiohttp.TCPConnector(limit=RELAY_MAX + 20, ssl=False),
                                                    headers={"User-Agent": UA})
        async with SHARED["relay"].get(st["url"], headers={"Icy-MetaData": "0"},
                                         timeout=aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=30)) as r:
            if r.status >= 400:
                return web.Response(status=502)
            resp = web.StreamResponse(headers={
                "Content-Type": r.headers.get("Content-Type", "audio/mpeg").split(";")[0],
                "Cache-Control": "no-cache", "Access-Control-Allow-Origin": "*"})
            await resp.prepare(req)
            async for chunk in r.content.iter_chunked(16384):
                if traffic["bytes"] >= RELAY_STOP:
                    break
                await resp.write(chunk)
            return resp
    except asyncio.CancelledError:
        raise
    except Exception:   # z. B. Lautsprecher hat aufgelegt: still beenden
        return resp if resp is not None and resp.prepared else web.Response(status=502)
    finally:
        relays["n"] -= 1


# ---------------- Server-Mix: ein durchgehender Stream, der nach jedem Lied zum nächsten Hit springt ----------------
# Für Alexa (und alles, was nur eine Adresse abspielen kann): Lied-Ende kommt aus den Titeln im Stream,
# Moderation/Werbung dazwischen wird übersprungen. Nur MP3-Sender mit gleicher Abtastrate (sonst stockt es).
mix_bad: set = set()        # Sender, die sich nicht eignen (kein MP3, keine Titel, Fehler)
_MP3_RATES = {0: 44100, 1: 48000, 2: 32000}


def mp3_rate(b: bytes):
    for i in range(min(len(b) - 4, 8000)):
        if b[i] == 0xFF and (b[i + 1] & 0xE0) == 0xE0:
            ver = (b[i + 1] >> 3) & 3
            if ver == 3 and ((b[i + 1] >> 1) & 3) == 1:     # MPEG-1 Layer III
                r = (b[i + 2] >> 2) & 3
                if r in _MP3_RATES and (b[i + 2] >> 4) not in (0, 15):
                    return _MP3_RATES[r]
    return None


def mix_fits(st, norm, title, genre):
    rx = re.compile(TAG_WORDS[genre]) if genre in TAG_WORDS else None
    if rx is not None:
        return bool(rx.search(st.get("tags", "")) or rx.search(title.lower()))
    return plays24(norm) >= 8


def mix_pick(genre, played, exclude):
    m = ids_map()
    t = time.time()
    rx = re.compile(TAG_WORDS[genre]) if genre in TAG_WORDS else None
    best, score = None, None
    for sid, n in fresh():
        st = m.get(sid)
        if not st or sid in exclude or sid in mix_bad or n["norm"] in played or not is_song(n["title"]) or TALK.search(n["title"]):
            continue
        if rx is not None and not (rx.search(st.get("tags", "")) or rx.search(n["title"].lower())):
            continue
        age = t - n["since"]
        if age > 150 or ending_soon(n, t):      # nur frisch angefangene Lieder
            continue
        sc = age + (240 if sid in ads else 0) - (0 if rx is not None else min(plays24(n["norm"]), 60) * 20)
        if score is None or sc < score:
            best, score = (st, n), sc
    return best


async def mix_song(resp, st, n, genre, played, state):
    """Spielt das Lied auf diesem Sender (und passende Folgelieder). False = Sender ungeeignet."""
    sess = SHARED.get("relay")
    if sess is None:
        sess = SHARED["relay"] = aiohttp.ClientSession(connector=aiohttp.TCPConnector(limit=RELAY_MAX + 20, ssl=False),
                                                       headers={"User-Agent": UA})
    async with sess.get(st["url"], headers={"Icy-MetaData": "1"},
                        timeout=aiohttp.ClientTimeout(total=None, sock_connect=8, sock_read=20)) as r:
        ctype = r.headers.get("Content-Type", "")
        metaint = int(r.headers.get("icy-metaint", "0") or 0)
        if r.status >= 400 or "mpeg" not in ctype or metaint <= 0:
            mix_bad.add(st["id"])
            return False
        expected = n["norm"]
        started = time.time()
        confirmed = False
        song_start = n["since"]
        dur = (covers.get(expected) or {}).get("dur", 0) if n.get("exact") else 0   # Anfang unbekannt: nicht nach Länge abbrechen
        first = True
        while True:
            audio = await r.content.readexactly(metaint)
            if first:
                rate = mp3_rate(audio)
                if state.get("rate") is None and rate:
                    state["rate"] = rate
                elif rate and state.get("rate") != rate:
                    mix_bad.add(st["id"])
                    return False
                first = False
            # Bis der erwartete Titel kommt (Vorab-Werbung, Sendername): nicht durchreichen, höchstens 25 s
            if confirmed or time.time() - started > 25:
                await resp.write(audio)
                count_relay(len(audio))
            ln = (await r.content.readexactly(1))[0] * 16
            if ln:
                meta = (await r.content.readexactly(ln)).decode("utf-8", "ignore")
                mt = re.search(r"StreamTitle='(.*?)';", meta)
                title = (mt.group(1) if mt else "").strip()
                norm = normalize(title)
                if norm == expected:
                    confirmed = True
                elif is_song(title) and not TALK.search(title):
                    if not confirmed and time.time() - started < 25:
                        expected, confirmed = norm, True     # Server war etwas veraltet: das ist das Lied
                    elif norm not in played and mix_fits(st, norm, title, genre):
                        expected, song_start = norm, time.time()   # passendes Folgelied: bleiben
                        dur = (covers.get(norm) or {}).get("dur", 0)
                        played.add(norm)
                    else:
                        return True
                elif confirmed:
                    return True      # Moderation, Jingle, Werbung: weiter zum nächsten Hit
            if confirmed and dur and time.time() - song_start > dur + 4:
                return True          # Lied laut Länge vorbei, Sender redet weiter
            if traffic["bytes"] >= RELAY_STOP:
                return True


def count_relay(n):
    pass   # Datenmenge zählt die Netzwerkkarte (siehe update_traffic)


async def h_mix(req):
    genre = (req.query.get("g") or "")[:20]
    first = req.query.get("first") or ""
    if not relay_ok():
        return web.Response(status=503, text="Pause")
    resp = web.StreamResponse(headers={"Content-Type": "audio/mpeg", "Cache-Control": "no-cache"})
    await resp.prepare(req)
    relays["n"] += 1
    played: set = set()
    state: dict = {}
    last = ""
    try:
        while True:
            if req.transport is None or req.transport.is_closing():
                break      # Hörer ist weg
            hit = None
            if first and first in ids_map() and first in now:
                hit = (ids_map()[first], now[first])
                first = ""
            hit = hit or mix_pick(genre, played, {last}) or mix_pick("", played, {last})
            if not hit:
                await asyncio.sleep(2)
                continue
            st, n = hit
            played.add(n["norm"])
            last = st["id"]
            if len(played) > 40:
                played.clear()
            try:
                await mix_song(resp, st, n, genre, played, state)
            except asyncio.CancelledError:
                raise
            except Exception:
                # Hörer weg? Dann aufhören. Sonst war nur der Sender schuld.
                if req.transport is None or req.transport.is_closing():
                    break
                mix_bad.add(st["id"])
    except Exception:
        pass
    finally:
        relays["n"] -= 1
    return resp


# ---------------- Alexa-Skill: "Alexa, sag Hit Radar, spiel Last Christmas" ----------------
PUBLIC = os.environ.get("PUBLIC_URL", "https://130-61-78-232.sslip.io")   # Alexa braucht https auf Port 443
TAG_WORDS = {"xmas": "christmas|weihnacht|xmas", "kids": "kinder|kids|children", "chill": "chill|lounge|relax|ambient",
             "party": "party|ballermann|apres", "80s": "80s|80er", "90s": "90s|90er", "2000s": "2000s|00s", "oldies": "oldies|60s|70s",
             "deutschrap": "deutschrap|deutsch rap", "rap": "hip ?hop|rap", "rock": "rock|metal", "schlager": "schlager",
             "dance": "dance|techno|house|edm|electro", "summer": "summer|hits", "jazz": "jazz|soul|blues", "classic": "classical|klassik",
             "latin": "latin|reggaeton", "country": "country", "charts": "charts|top 40|hits|pop"}
_alexa_certs: dict = {}


async def alexa_verify(req, body: bytes) -> bool:
    """Prüft, dass die Anfrage wirklich von Amazon kommt (Pflicht für Alexa-Skills)."""
    try:
        from urllib.parse import urlparse
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        url = req.headers.get("SignatureCertChainUrl", "")
        sig = req.headers.get("Signature-256", "")
        u = urlparse(url)
        path = os.path.normpath(u.path)
        if u.scheme.lower() != "https" or (u.hostname or "").lower() != "s3.amazonaws.com" \
                or not path.startswith("/echo.api/") or (u.port not in (None, 443)) or not sig:
            return False
        certs = _alexa_certs.get(url)
        if not certs:
            async with SHARED["session"].get(url, ssl=True, timeout=aiohttp.ClientTimeout(total=5)) as r:
                pem = await r.read()
            certs = x509.load_pem_x509_certificates(pem)
            try:  # ganze Kette bis zu einer vertrauenswürdigen Stelle prüfen
                from cryptography.x509.verification import PolicyBuilder, Store
                with open("/etc/ssl/certs/ca-certificates.crt", "rb") as f:
                    store = Store(x509.load_pem_x509_certificates(f.read()))
                PolicyBuilder().store(store).build_server_verifier(x509.DNSName("echo-api.amazon.com")).verify(certs[0], certs[1:])
            except ImportError:
                san = certs[0].extensions.get_extension_for_class(x509.SubjectAlternativeName).value
                if "echo-api.amazon.com" not in san.get_values_for_type(x509.DNSName):
                    return False
            _alexa_certs[url] = certs
        c = certs[0]
        nowdt = __import__("datetime").datetime.now(__import__("datetime").timezone.utc)
        if not (c.not_valid_before_utc <= nowdt <= c.not_valid_after_utc):
            _alexa_certs.pop(url, None)
            return False
        c.public_key().verify(base64.b64decode(sig), body, padding.PKCS1v15(), hashes.SHA256())
        ts = json.loads(body)["request"]["timestamp"]
        t = __import__("datetime").datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
        return abs(time.time() - t) <= 150
    except Exception as e:
        print("Alexa-Prüfung fehlgeschlagen:", e, flush=True)
        return False


def ending_soon(n, t=None) -> bool:
    """Lied ist gleich vorbei: weniger als 1 Minute übrig (Länge bekannt) oder schon über 4 Minuten alt."""
    if not n.get("exact"):
        return True       # Anfang unbekannt: kann schon fast vorbei sein
    age = (t or time.time()) - n["since"]
    dur = (covers.get(n["norm"]) or {}).get("dur", 0)
    return dur - age < 60 if dur else age > 240


def real_song(norm: str, title: str) -> bool:
    """Echtes Lied (bei Deezer gefunden), keine Hörspielfolge, kein Sender-Slogan. Unbekannt: nachschlagen, erst mal nein."""
    c = covers.get(norm)
    if c is None:
        if len(cover_queue) < 5000:
            cover_queue[norm] = title
        return False
    return bool(c)


def plays24(norm: str) -> int:
    h0 = int(time.time() // 3600) - 24
    return sum(c.get(norm, 0) for h, c in chart_hours.items() if h >= h0)


def alexa_pick(query: str = "", genre: str = "", exclude: str = ""):
    """Bester Sender: Lied läuft gerade (frisch angefangen), möglichst ohne Vorab-Werbung."""
    m = ids_map()
    t = time.time()
    words = [w for w in normalize(query).split() if w]
    rx = re.compile(TAG_WORDS.get(genre, "")) if genre and genre in TAG_WORDS else None
    best, score = None, None
    for sid, n in fresh():
        st = m.get(sid)
        # Gewünschtes Lied: auch nehmen, wenn der Anfang unbekannt ist; sonst nur frische Lieder
        if not st or sid == exclude or not is_song(n["title"]) or TALK.search(n["title"]) or \
                (ending_soon(n, t) and (n.get("exact") or not words)):
            continue
        if words:
            if not all(w in n["norm"] for w in words):
                continue
        elif rx is not None and not (rx.search(st.get("tags", "")) or rx.search(n["title"].lower())):
            continue
        age = t - n["since"]
        known = 0 if words else min(plays24(n["norm"]), 60) * 20
        sc = age + (240 if sid in ads else 0) + (600 if age > 200 else 0) - known
        if score is None or sc < score:
            best, score = (st, n), sc
    return best


def alexa_play(st, n, token: str, speech: str = ""):
    # Durchgehender Mix: nach dem Lied geht es automatisch mit dem nächsten passenden Hit weiter
    kind, genre = (token.split("|") + ["", ""])[:2]
    url = f"{PUBLIC}/mix?g={genre if kind == 'mix' else ''}&first={st['id']}&r={int(time.time())}"
    out = {"version": "1.0", "response": {"shouldEndSession": True, "directives": [{
        "type": "AudioPlayer.Play", "playBehavior": "REPLACE_ALL",
        "audioItem": {"stream": {"url": url, "token": token, "offsetInMilliseconds": 0},
                      "metadata": {"title": n["title"][:100], "subtitle": st["name"][:100]}}}]}}
    if speech:
        out["response"]["outputSpeech"] = {"type": "PlainText", "text": speech}
    return out


def alexa_say(text: str, end: bool = True):
    return {"version": "1.0", "response": {"outputSpeech": {"type": "PlainText", "text": text}, "shouldEndSession": end}}


def spoken(title: str) -> str:
    a, _, b = title.partition(" - ")
    return f"{b.strip()} von {a.strip()}" if b else title


async def h_alexa(req):
    body = await req.read()
    if not await alexa_verify(req, body):
        return web.Response(status=400)
    data = json.loads(body)
    r = data.get("request", {})
    typ = r.get("type", "")
    token = (data.get("context", {}).get("AudioPlayer", {}) or {}).get("token", "") or r.get("token", "")
    if not relay_ok():
        return web.json_response(alexa_say("Hit Radar macht gerade eine Pause. Bitte versuch es später nochmal."))

    def mix(genre="", exclude="", speech=True):
        hit = alexa_pick(genre=genre, exclude=exclude) or alexa_pick(exclude=exclude)
        if not hit:
            return alexa_say("Gerade finde ich nichts Passendes. Versuch es gleich nochmal.")
        st, n = hit
        return alexa_play(st, n, f"mix|{genre}|{st['id']}", f"Hit-Mix. {spoken(n['title'])}." if speech else "")

    if typ == "LaunchRequest":
        return web.json_response(mix())
    if typ == "IntentRequest":
        intent = r.get("intent", {})
        name = intent.get("name", "")
        slots = {k: (v.get("value") or "") for k, v in (intent.get("slots") or {}).items()}
        if name == "PlaySongIntent" and slots.get("song"):
            q = slots["song"]
            hit = alexa_pick(query=q)
            if not hit:
                return web.json_response(alexa_say(f"{q} läuft gerade auf keinem Sender. Sag zum Beispiel: Hit-Mix.", end=False))
            st, n = hit
            return web.json_response(alexa_play(st, n, f"song|{q}|{st['id']}", f"{spoken(n['title'])} auf {st['name']}."))
        if name == "HitMixIntent":
            g = (wish_rules(slots.get("genre", "")) or {}).get("genre", "") if slots.get("genre") else ""
            return web.json_response(mix(g))
        if name in ("AMAZON.NextIntent", "AMAZON.ShuffleOnIntent"):
            g = token.split("|")[1] if token.startswith("mix|") else ""
            return web.json_response(mix(g, exclude=token.rsplit("|", 1)[-1], speech=False))
        if name in ("AMAZON.ResumeIntent", "AMAZON.StartOverIntent", "AMAZON.RepeatIntent", "AMAZON.PreviousIntent"):
            sid = token.rsplit("|", 1)[-1] if token else ""
            st = ids_map().get(sid)
            if st:
                n = now.get(sid) or {"title": st["name"]}
                return web.json_response(alexa_play(st, n, token))
            return web.json_response(mix())
        if name in ("AMAZON.PauseIntent", "AMAZON.StopIntent", "AMAZON.CancelIntent"):
            return web.json_response({"version": "1.0", "response": {"shouldEndSession": True,
                                                                  "directives": [{"type": "AudioPlayer.Stop"}]}})
        if name == "AMAZON.HelpIntent":
            return web.json_response(alexa_say("Sag zum Beispiel: spiel Last Christmas. Oder: starte den Hit-Mix. "
                                               "Oder: Hit-Mix mit Rock. Was möchtest du hören?", end=False))
        if name == "AMAZON.FallbackIntent":
            return web.json_response(alexa_say("Das habe ich nicht verstanden. Sag zum Beispiel: spiel Last Christmas.", end=False))
        return web.json_response(alexa_say("Das geht bei Hit Radar leider nicht."))
    if typ == "AudioPlayer.PlaybackFailed" and token:
        # Sender geht nicht: gleiches Lied woanders oder nächster Hit
        kind, q, sid = (token.split("|") + ["", "", ""])[:3]
        hit = alexa_pick(query=q, exclude=sid) if kind == "song" else None
        if hit:
            st, n = hit
            return web.json_response({"version": "1.0", "response": {"directives":
                alexa_play(st, n, f"song|{q}|{st['id']}")["response"]["directives"]}})
        return web.json_response({"version": "1.0", "response": {"directives":
            mix(q if kind == "mix" else "", exclude=sid, speech=False)["response"]["directives"]}})
    # Andere Ereignisse (gestartet, gestoppt, Sitzung beendet): nichts sagen
    return web.json_response({"version": "1.0", "response": {}})


# ---------------- API ----------------

def fresh():
    t = time.time()
    return [(sid, n) for sid, n in now.items() if t - n["time"] < FRESH_SECONDS]


by_id = lambda: {s["id"]: s for s in stations}


def item(st, n):
    return {"id": st["id"], "name": st["name"], "url": st["url"], "favicon": st["favicon"], "cc": st.get("cc", ""),
            "title": n["title"], "since": int(n["since"]), "seen": int(n["time"]), "exact": bool(n.get("exact"))}


def gz(resp):
    # Komprimiert senden: spart Daten in der App und Traffic am Server
    resp.enable_compression()
    return resp


async def h_health(_):
    return web.json_response({"ok": True, "stations": len(stations), "live": len(fresh()),
                              "checked": stats["checked"], "queue": len(due),
                              "traffic_gb": round(traffic["bytes"] / 1e9, 1), "relay": relay_ok(), "relays": relays["n"],
                              "spar": SPAR["low"], "modus": SPAR["modus"], "modus_quelle": SPAR["quelle"], "meldung": MELDUNG["jetzt"],
                              "spiel_stufe": SPAR["stufe"]})


_search_cache: dict = {}  # Suchwörter -> (Zeit, Antwort)


async def h_search(req):
    words = tuple(sorted(w for w in normalize(req.query.get("q", "")).split() if w))
    if not words:
        return web.json_response([])
    # Gleiche Suche von vielen Nutzern (z. B. ein Hit): 15 s lang dieselbe Antwort
    hit = _search_cache.get(words)
    if hit and time.time() - hit[0] < 15:
        return gz(web.json_response(hit[1]))
    ids = ids_map()
    hits = [item(ids[sid], n) for sid, n in fresh() if sid in ids and all(w in n["norm"] for w in words)]
    hits.sort(key=lambda h: -h["since"])
    hits = hits[:200]
    if len(_search_cache) > 2000:
        _search_cache.clear()
    _search_cache[words] = (time.time(), hits)
    return gz(web.json_response(hits))


_now_cache: dict = {}   # (limit, music) -> (Zeit, gzip-Bytes)
_ids_cache = {"n": -1, "map": {}}


def ids_map():
    # Sender-Verzeichnis nur neu bauen, wenn sich die Liste geändert hat
    if _ids_cache["n"] != len(stations):
        _ids_cache.update(n=len(stations), map={s["id"]: s for s in stations})
    return _ids_cache["map"]


async def h_now(req):
    limit = min(int(req.query.get("limit", "5000")), 20000)
    music = req.query.get("music") == "1"
    key = (limit, music)
    hit = _now_cache.get(key)
    # Viele Nutzer: Antwort 15 s lang fertig gepackt wiederverwenden (kostet dann fast nichts)
    if not hit or time.time() - hit[0] > 15:
        ids = ids_map()
        # music=1: nur Liedtitel ("Künstler - Titel"), halb so viele Daten
        out = [item(ids[sid], n) for sid, n in fresh()
               if sid in ids and (not music or " - " in n["title"] or " – " in n["title"])]
        out.sort(key=lambda h: -h["since"])
        body = gzip.compress(json.dumps(out[:limit], separators=(",", ":")).encode(), 6)
        hit = (time.time(), body)
        _now_cache[key] = hit
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=hit[1], content_type="application/json",
                            headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(hit[1]), content_type="application/json")


# ---------------- Schnelles Abgleichen (nur Änderungen) ----------------
# Alle 15 s ein Schnappschuss mit Versionsnummer. Die App schickt ihre letzte Version mit
# und bekommt nur, was sich seitdem geändert hat – bei bekannten Sendern ohne Name/Adresse.
_snaps: list = []          # [(Version, Zeit, {sid: (title, since, seen)})]
_diff_cache: dict = {}     # (from, to) -> gzip-Bytes
_ver = {"n": 0}


def snapshot():
    t = time.time()
    if _snaps and t - _snaps[-1][1] < 15:
        return _snaps[-1]
    snap = {sid: (n["title"], int(n["since"]), int(n["time"]), 1 if n.get("exact") else 0)
            for sid, n in fresh() if is_song(n["title"])}
    _ver["n"] += 1
    _snaps.append((_ver["n"], t, snap))
    del _snaps[:-60]  # ca. 15 Minuten Verlauf
    _diff_cache.clear()
    return _snaps[-1]


async def h_sync(req):
    ver, t, snap = snapshot()
    try:
        frm = int(req.query.get("from", "0"))
    except ValueError:
        frm = 0
    key = (frm, ver)
    body = _diff_cache.get(key)
    if body is None:
        ids = ids_map()
        old = next((s for v, _, s in _snaps if v == frm), None)
        if old is None:
            # Erstes Mal oder zu alt: alles, mit Senderdaten
            # Reihenfolge: ..., Titel, seit, gesehen, Land, genau (Land an Stelle 7 für ältere Apps)
            rows = [[sid, ids[sid]["name"], ids[sid]["url"], ids[sid]["favicon"], *val[:3], ids[sid].get("cc", ""), val[3]]
                    for sid, val in snap.items() if sid in ids]
            out = {"v": ver, "t": int(t), "full": 1, "rows": rows}
        else:
            rows = []
            for sid, val in snap.items():
                o = old.get(sid)
                if o is not None and o[0] == val[0]:
                    continue  # unverändert
                if o is not None:
                    rows.append([sid, *val])  # Sender kennt die App schon
                elif sid in ids:
                    rows.append([sid, ids[sid]["name"], ids[sid]["url"], ids[sid]["favicon"], *val[:3], ids[sid].get("cc", ""), val[3]])
            gone = [sid for sid in old if sid not in snap]
            out = {"v": ver, "t": int(t), "rows": rows, "gone": gone}
        body = gzip.compress(json.dumps(out, separators=(",", ":"), ensure_ascii=False).encode(), 6)
        _diff_cache[key] = body
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=body, content_type="application/json", headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(body), content_type="application/json")


async def main():
    app = web.Application()
    app.add_routes([web.get("/health", h_health), web.get("/search", h_search), web.get("/now", h_now),
                    web.get("/sync", h_sync), web.get("/charts", h_charts),
                    web.get("/similar", h_similar), web.get("/wish", h_wish),
                    web.get("/ad", h_ad), web.get("/ads", h_ads), web.get("/stations", h_stations),
                    web.get("/cover", h_cover), web.get("/verify", h_verify), web.get("/list", h_list),
                    web.get("/event", h_event), web.get("/popular", h_popular),
                    web.get("/relay", h_relay), web.post("/alexa", h_alexa),
                    web.get("/rising", h_rising), web.get("/mix", h_mix)])
    runner = web.AppRunner(app, access_log=None)  # keine Such-/IP-Protokolle speichern
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()
    print(f"Radar-Server läuft auf Port {PORT}", flush=True)
    asyncio.create_task(follow_game())
    await scanner()


if __name__ == "__main__":
    asyncio.run(main())
