#!/bin/bash
# Hitradar ordnet sich dem Spiel unter: eigene KI mit kleinerem Rechenanteil. Danach prüfen (keine Geheimnisse ausgeben).
systemctl set-property llm.service CPUWeight=25 >/dev/null 2>&1 && echo "llm.service: CPUWeight 25"
sleep 60
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-2 min" --no-pager | grep -iE "Spiel|Traceback|Error" | tail -5
echo "== Spiel =="; curl -s -m 5 http://127.0.0.1:8100/api/last; echo
systemctl show -p CPUWeight radar.service llm.service
