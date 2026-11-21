#!/bin/bash
# Wird einmal auf dem Server ausgeführt, wenn sich diese Datei ändert (über GitHub Actions).
# KI prüfen: warten, bis sie läuft (max. 12 Minuten), dann einen Wunsch testen
for i in $(seq 1 6); do
  systemctl is-active --quiet llm && break
  sleep 10
done
echo "--- Einrichtung (Ende) ---"; tail -5 /var/log/llm-setup.log
echo "KI-Dienst: $(systemctl is-active llm)"
sleep 5
for q in "was Ruhiges zum Einschlafen" "Party wie auf Mallorca" "etwas von Helene Fischer" "Musik für eine Autofahrt im Sommer" "Lieder wie von Queen"; do
  echo "Wunsch: $q"
  START=$(date +%s)
  curl -s -m 40 "http://localhost:8081/wish?q=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$q")"; echo
  echo "Dauer: $(( $(date +%s) - START )) s"
done
echo "Ähnlich zu 'Wham - Last Christmas':"
curl -s "http://localhost:8081/similar?q=Wham%20Last%20Christmas" | head -c 600; echo
