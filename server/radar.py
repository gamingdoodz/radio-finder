"""
Radar-Server: beobachtet rund um die Uhr tausende Internetradios und weiß,
was gerade wo läuft. Die App fragt nur noch hier – Suche in unter einer Sekunde.

API:
  GET /health                -> {"ok": true, "stations": n, "live": n}
  GET /search?q=<text>       -> Sender, auf denen das gerade läuft
  GET /now?limit=<n>         -> alles, was gerade läuft (neueste zuerst)
"""
import asyncio
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


def put(st: dict, title: str):
    title = html.unescape(title).strip()
    t = time.time()
    old = now.get(st["id"])
    since = old["since"] if old and old["title"] == title and t - old["time"] < FRESH_SECONDS else t
    now[st["id"]] = {"title": title, "norm": normalize(title), "since": since, "time": t}


# ---------------- Senderliste ----------------

async def load_stations(session: aiohttp.ClientSession):
    global stations
    queries = [
        f"limit={MAX_STATIONS}",
        "countrycode=DE&limit=3000",
        "countrycode=AT&limit=600",
        "countrycode=CH&limit=600",
    ]
    found: dict[str, dict] = {}
    for q in queries:
        for host in RB_SERVERS:
            try:
                url = f"https://{host}/json/stations/search?{q}&order=clickcount&reverse=true&hidebroken=true"
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=300)) as r:
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
                    })
                break
            except Exception:
                continue
    if found:
        stations = list(found.values())
    print(f"Senderliste: {len(stations)} Sender", flush=True)


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


async def scan_round(session):
    sem = asyncio.Semaphore(CONCURRENCY)
    groups: dict = {}
    for st in stations:
        groups.setdefault(server_key(st["url"]), []).append(st)

    async def do_group(key, group):
        titles = None
        if key and key not in no_status:
            async with sem:
                titles = await icecast_titles(session, key)
            if titles is None:
                no_status.add(key)
        for st in group:
            t = None
            if titles:
                path = urlparse(st["url"]).path or "/"
                t = titles.get(path) or titles.get(path.rstrip("/"))
            if t is None:
                async with sem:
                    t = await icy_title(session, st["url"])
            if t:
                put(st, t)

    started = time.time()
    await asyncio.gather(*(do_group(k, g) for k, g in groups.items()))
    last_round.update(seconds=round(time.time() - started), at=int(time.time()))
    print(f"Runde fertig: {len(stations)} Sender in {last_round['seconds']} s, live: {len(fresh())}", flush=True)


async def scanner():
    conn = aiohttp.TCPConnector(limit=CONCURRENCY, ttl_dns_cache=600, ssl=False)
    async with aiohttp.ClientSession(connector=conn, headers={"User-Agent": UA}) as session:
        last_list = 0.0
        while True:
            if time.time() - last_list > 24 * 3600 or not stations:
                await load_stations(session)
                last_list = time.time()
            try:
                await scan_round(session)
            except Exception as e:
                print("Fehler:", e, flush=True)
            await asyncio.sleep(5)


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
    return web.json_response({"ok": True, "stations": len(stations), "live": len(fresh()), "round": last_round})


async def h_search(req):
    words = [w for w in normalize(req.query.get("q", "")).split() if w]
    if not words:
        return web.json_response([])
    ids = by_id()
    hits = [item(ids[sid], n) for sid, n in fresh() if sid in ids and all(w in n["norm"] for w in words)]
    hits.sort(key=lambda h: -h["since"])
    return gz(web.json_response(hits[:200]))


async def h_now(req):
    limit = min(int(req.query.get("limit", "5000")), 20000)
    ids = by_id()
    out = [item(ids[sid], n) for sid, n in fresh() if sid in ids]
    out.sort(key=lambda h: -h["since"])
    return gz(web.json_response(out[:limit]))


async def main():
    app = web.Application()
    app.add_routes([web.get("/health", h_health), web.get("/search", h_search), web.get("/now", h_now)])
    runner = web.AppRunner(app, access_log=None)  # keine Such-/IP-Protokolle speichern
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()
    print(f"Radar-Server läuft auf Port {PORT}", flush=True)
    await scanner()


if __name__ == "__main__":
    asyncio.run(main())
