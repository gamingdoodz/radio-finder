#!/bin/bash
# Serverabsprache (neue Vorgabe): Meldung und gelesene Stufe prüfen
sleep 100
echo "== Meldung =="; ls -la /var/lib/serverabsprache /var/lib/serverabsprache/dienste 2>&1; head -c 600 /var/lib/serverabsprache/dienste/hitradar.json 2>&1; echo
echo "== Stufe vom Spiel =="; curl -s -m 5 http://127.0.0.1:8100/api/last | python3 -c 'import sys,json; d=json.load(sys.stdin); print("budget.hitradar:", (d.get("budget") or {}).get("hitradar"))' 2>&1
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-3 min" --no-pager | grep -iE "Serverabsprache|Senderliste|Traceback|Error" | tail -8
