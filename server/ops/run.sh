#!/bin/bash
# Wird einmal auf dem Server ausgeführt, wenn sich diese Datei ändert (über GitHub Actions).
# Test: Verbindung und Zustand des Servers
echo "Server: $(hostname) · läuft seit: $(uptime -p)"
free -h | head -2
df -h / | tail -1
nproc
echo "Radar-Dienst: $(systemctl is-active radar)"
