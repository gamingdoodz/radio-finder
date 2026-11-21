"""
Radar-Server: beobachtet rund um die Uhr tausende Internetradios und weiß,
was gerade wo läuft. Die App fragt nur noch hier – Suche in unter einer Sekunde.

API:
  GET /health                -> {"ok": true, "stations": n, "live": n}
  GET /search?q=<text>       -> Sender, auf denen das gerade läuft
  GET /now?limit=<n>         -> alles, was gerade läuft (neueste zuerst)
"""
import asyncio
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


def is_song(t: str) -> bool:
    return " - " in t or " – " in t


def put(st: dict, title: str):
    title = html.unescape(title).strip()
    t = time.time()
    old = now.get(st["id"])
    since = old["since"] if old and old["title"] == title and t - old["time"] < FRESH_SECONDS else t
    norm = normalize(title)
    now[st["id"]] = {"title": title, "norm": norm, "since": since, "time": t}
    # Neues Lied auf einem Sender aus DE/AT/CH: für die Radio-Charts zählen
    if since == t and is_song(title) and not TALK.search(title):
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
        for k, d in neighbors.items():
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
        top = sorted(total.items(), key=lambda kv: -kv[1])[:100]
        out = [{"title": chart_titles.get(k, k), "plays": n} for k, n in top if n >= 3]
        _charts_cache.update(t=time.time(), body=gzip.compress(json.dumps(out, ensure_ascii=False).encode(), 6))
    if "gzip" in req.headers.get("Accept-Encoding", ""):
        return web.Response(body=_charts_cache["body"], content_type="application/json", headers={"Content-Encoding": "gzip"})
    return web.Response(body=gzip.decompress(_charts_cache["body"]), content_type="application/json")


# ---------------- Senderliste ----------------

STATIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stations_cache.json")


def load_cached_stations() -> bool:
    """Beim Start sofort die zuletzt gespeicherte Senderliste nehmen (kein Warten auf radio-browser)."""
    global stations
    try:
        with open(STATIONS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        if data:
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
    if found and (not stations or len(found) >= 0.8 * len(stations)):
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
    import heapq
    while True:
        if not due:
            await asyncio.sleep(1)
            continue
        when, sid = due[0]
        wait = when - time.time()
        if wait > 0:
            await asyncio.sleep(min(wait, 1))
            continue
        heapq.heappop(due)
        st = ids.get(sid)
        if st is None:
            continue
        try:
            nxt = await check(session, st)
        except Exception:
            nxt = 600
        heapq.heappush(due, (time.time() + nxt, sid))


async def scanner():
    conn = aiohttp.TCPConnector(limit=CONCURRENCY, limit_per_host=20, ttl_dns_cache=600, ssl=False)
    async with aiohttp.ClientSession(connector=conn, headers={"User-Agent": UA}) as session:
        load_charts()
        load_similar()
        ids: dict = {}
        # Sofort mit der gespeicherten Liste loslegen, die frische Liste kommt danach
        workers = [asyncio.create_task(worker(session, ids)) for _ in range(CONCURRENCY)]
        if load_cached_stations():
            ids.update({s["id"]: s for s in stations})
            schedule_all()
        if await load_stations(session):
            ids.clear(); ids.update({s["id"]: s for s in stations})
            schedule_all()
        while True:
            await asyncio.sleep(60)
            el = time.time() - stats["since"]
            last_round.update(seconds=int(el), at=int(time.time()))
            print(f"{len(stations)} Sender, {stats['checked']} Abfragen, live: {len(fresh())}", flush=True)
            if int(el) // 60 % 10 == 0:
                save_charts()
            if int(el) // 60 % 30 == 15:
                save_similar()
            # Einmal am Tag neu – und öfter, solange die Liste zu klein ist
            if el > 24 * 3600 or len(stations) < 20000 and int(el) // 60 % 30 == 29:
                if await load_stations(session):
                    ids.clear(); ids.update({s["id"]: s for s in stations})
                    schedule_all()
                stats.update(checked=0, since=time.time())


# ---------------- API ----------------

def fresh():
    t = time.time()
    return [(sid, n) for sid, n in now.items() if t - n["time"] < FRESH_SECONDS]


by_id = lambda: {s["id"]: s for s in stations}


def item(st, n):
    return {"id": st["id"], "name": st["name"], "url": st["url"], "favicon": st["favicon"],
            "title": n["title"], "since": int(n["since"]), "seen": int(n["time"])}


def gz(resp):
    # Komprimiert senden: spart Daten in der App und Traffic am Server
    resp.enable_compression()
    return resp


async def h_health(_):
    return web.json_response({"ok": True, "stations": len(stations), "live": len(fresh()),
                              "checked": stats["checked"], "queue": len(due)})


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
    snap = {sid: (n["title"], int(n["since"]), int(n["time"])) for sid, n in fresh() if is_song(n["title"])}
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
            rows = [[sid, ids[sid]["name"], ids[sid]["url"], ids[sid]["favicon"], *val]
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
                    rows.append([sid, ids[sid]["name"], ids[sid]["url"], ids[sid]["favicon"], *val])
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
                    web.get("/similar", h_similar), web.get("/wish", h_wish)])
    runner = web.AppRunner(app, access_log=None)  # keine Such-/IP-Protokolle speichern
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()
    print(f"Radar-Server läuft auf Port {PORT}", flush=True)
    await scanner()


if __name__ == "__main__":
    asyncio.run(main())
