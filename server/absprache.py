#!/usr/bin/env python3
"""Serverabsprache mit Valdrakor für Updates und Neustarts von Hitradar.

Regel des Spiels (26.11.):
  - Vor einem eigenen Update/Neustart "jetzt":"update" mit "bis" (höchstens 1 h) in dienste/hitradar.json eintragen.
  - Erst starten, wenn in lage.json updateErlaubt true ist. Steht bei updateDran "spiel", warten.
    Es läuft immer nur ein Update gleichzeitig, das Spiel hat immer Vorrang.
  - Danach sofort zurück auf "leicht".

Aufruf:
  absprache.py melde <leicht|schwer|ruhe|update> [sekunden]
  absprache.py warte [max_sekunden]   -> Exit 0, sobald das Update erlaubt ist; Exit 1 nach Ablauf
  absprache.py darf                   -> Exit 0, wenn das Update jetzt erlaubt ist
"""
import json
import os
import sys
import time

LAGE_FILE = os.environ.get("ABSPRACHE_LAGE", "/run/serverabsprache/lage.json")
DIENST_FILE = os.environ.get("ABSPRACHE_DIENST", "/var/lib/serverabsprache/dienste/hitradar.json")
MAX_BIS = {"update": 3600, "schwer": 6 * 3600}


def ram_mb() -> int:
    total = 0
    for unit in ("radar.service", "llm.service"):
        try:
            base = f"/sys/fs/cgroup/system.slice/{unit}/"
            cur = int(open(base + "memory.current").read().strip())
            stat = dict(l.split() for l in open(base + "memory.stat").read().splitlines())
            total += max(0, cur - int(stat.get("inactive_file", 0)))
        except Exception:
            pass
    if total <= 0:
        return 1200
    return int(-(-(total / (1024 * 1024)) // 100) * 100)


def melde(jetzt: str, sek: int = 0):
    now_ms = int(time.time() * 1000)
    bis = 0
    if jetzt in MAX_BIS:
        bis = now_ms + min(max(sek, 60), MAX_BIS[jetzt]) * 1000
    cpu = 50
    try:
        with open(DIENST_FILE, encoding="utf-8") as h:
            cpu = int(json.load(h).get("cpu") or 50)     # gemessener Wert von radar.py
    except Exception:
        pass
    info = {"prio": "hitradar", "was": "Radio und KI", "unit": "radar.service", "jetzt": jetzt, "bis": bis,
            "cpu": cpu, "ramMb": ram_mb(), "t": now_ms}
    os.makedirs(os.path.dirname(DIENST_FILE), exist_ok=True)
    tmp = DIENST_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as h:
        json.dump(info, h)
    os.chmod(tmp, 0o644)
    os.replace(tmp, DIENST_FILE)
    print(f"Serverabsprache: gemeldet {jetzt}", flush=True)


def _feld(d, name):
    """Feld oben in lage.json oder unter hitradar / budget.hitradar."""
    if not isinstance(d, dict):
        return None
    if name in d:
        return d[name]
    for weg in (("hitradar",), ("budget", "hitradar"), ("update",)):
        x = d
        for k in weg:
            x = x.get(k) if isinstance(x, dict) else None
        if isinstance(x, dict) and name in x:
            return x[name]
    return None


def darf() -> tuple[bool, str]:
    try:
        if time.time() - os.path.getmtime(LAGE_FILE) > 120:
            return False, "lage.json älter als 2 Minuten"
        with open(LAGE_FILE, encoding="utf-8") as h:
            d = json.load(h)
    except FileNotFoundError:
        return False, "lage.json fehlt"
    except Exception:
        return False, "lage.json nicht lesbar"
    dran = str(_feld(d, "updateDran") or "").strip().lower()
    if dran == "spiel":
        return False, "Spiel rollt aus (updateDran spiel)"
    if dran and dran != "hitradar":
        return False, f"anderer Dienst dran ({dran})"
    if _feld(d, "updateErlaubt") is not True:
        return False, "updateErlaubt nicht true"
    return True, "erlaubt"


def warte(max_s: int) -> bool:
    ende = time.time() + max_s
    letzter = ""
    while True:
        ok, grund = darf()
        if ok:
            print("Serverabsprache: Update erlaubt", flush=True)
            return True
        if grund != letzter:
            print(f"Serverabsprache: warte ({grund})", flush=True)
            letzter = grund
        if time.time() >= ende:
            return False
        time.sleep(15)


if __name__ == "__main__":
    a = sys.argv[1:]
    cmd = a[0] if a else ""
    if cmd == "melde" and len(a) >= 2:
        melde(a[1], int(a[2]) if len(a) > 2 else 0)
    elif cmd == "warte":
        sys.exit(0 if warte(int(a[1]) if len(a) > 1 else 25 * 60) else 1)
    elif cmd == "darf":
        ok, grund = darf()
        print(grund)
        sys.exit(0 if ok else 1)
    else:
        print(__doc__)
        sys.exit(2)
