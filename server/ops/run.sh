#!/bin/bash
sleep 240
curl -s https://130-61-78-232.sslip.io:8080/health; echo
journalctl -u radar --since "-5 min" --no-pager | grep -iE "Traceback|Error" | tail -5
python3 - <<'PY'
import json
c = json.load(open("/root/radio-finder/server/covers.json")) if __import__("os").path.exists("/root/radio-finder/server/covers.json") else {}
print("Cover gespeichert:", len(c), "davon mit Länge:", sum(1 for v in c.values() if v and v.get("dur")))
PY
