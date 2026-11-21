#!/bin/bash
# Wird einmal auf dem Server ausgeführt, wenn sich diese Datei ändert (über GitHub Actions).
# Nach dem Neustart 4 Minuten warten und prüfen, ob die volle Senderliste geladen wurde
sleep 240
curl -s http://localhost:8080/health; echo
journalctl -u radar -n 8 --no-pager | grep -i "sender" || true
