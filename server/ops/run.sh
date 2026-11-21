#!/bin/bash
# Wird einmal auf dem Server ausgeführt, wenn sich diese Datei ändert (über GitHub Actions).
# KI im Hintergrund einrichten (Übersetzen dauert ca. 15 Minuten), Fortschritt in /var/log/llm-setup.log
nohup bash /root/radio-finder/server/llm.sh > /var/log/llm-setup.log 2>&1 &
echo "KI-Einrichtung gestartet"
