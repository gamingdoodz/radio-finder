#!/bin/bash
# Serverabsprache: Anmeldung von Hitradar und gelesener Modus prüfen
sleep 90
echo "== Anmeldung =="; ls -la /var/lib/serverabsprache 2>&1; head -c 1500 /var/lib/serverabsprache/hitradar.json 2>&1; echo
echo "== Lage =="; ls -la /run/serverabsprache 2>&1
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-3 min" --no-pager | grep -iE "Serverabsprache|Traceback|Error" | tail -5
