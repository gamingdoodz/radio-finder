#!/bin/bash
# Wird einmal auf dem Server ausgeführt, wenn sich diese Datei ändert (über GitHub Actions).
# HTTPS einrichten
bash /root/radio-finder/server/https.sh
echo "Normal (Port 80):"
curl -s -m 5 http://localhost/health; echo
