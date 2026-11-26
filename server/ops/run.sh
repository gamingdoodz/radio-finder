#!/bin/bash
# Update-Regel (26.11.): Meldung, Update-Freigabe aus lage.json und Stufe prüfen
echo "== Meldung =="; head -c 600 /var/lib/serverabsprache/dienste/hitradar.json 2>&1; echo
echo "== Update-Freigabe =="; python3 /root/radio-finder/server/absprache.py darf 2>&1
python3 -c 'import json; d=json.load(open("/run/serverabsprache/lage.json")); print("Felder:", sorted(d)); print("updateErlaubt:", d.get("updateErlaubt"), "updateDran:", d.get("updateDran"))' 2>&1
ls -la /var/lib/hitradar-neustart-offen 2>&1
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-30 min" --no-pager | grep -iE "Serverabsprache|Traceback|Error" | tail -8
