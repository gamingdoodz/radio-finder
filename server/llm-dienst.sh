#!/bin/bash
# KI nur bei Bedarf: llm.service läuft erst beim ersten Wunsch an und geht nach 30 Minuten ohne Anfrage wieder aus.
# Spart die ~1 GB Arbeitsspeicher der KI fast den ganzen Tag. radar.py spricht weiter mit 127.0.0.1:8090.
# Aufbau: llm-proxy.socket (8090) -> llm-proxy.service (systemd-socket-proxyd) -> llm.service (8091).
# Als root ausführen; vorher Update-Absprache (absprache.py), weil die KI dabei neu startet.
set -e
PROXYD=/lib/systemd/systemd-socket-proxyd
[ -x "$PROXYD" ] || PROXYD=/usr/lib/systemd/systemd-socket-proxyd
if [ ! -x "$PROXYD" ] || [ ! -x /opt/llama.cpp/build/bin/llama-server ]; then
  echo "KI bei Bedarf nicht möglich (systemd-socket-proxyd oder llama-server fehlt), alles bleibt wie es ist"; exit 0
fi
cat > /etc/systemd/system/llm.service <<UNIT
[Unit]
Description=Hitradar KI (Wünsch dir was), startet nur bei Bedarf
StopWhenUnneeded=yes

[Service]
ExecStart=/opt/llama.cpp/build/bin/llama-server -m /opt/models/qwen.gguf --host 127.0.0.1 --port 8091 -c 1024 -t 2 --parallel 1 --no-warmup
ExecStartPost=/bin/sh -c 'for i in \$(seq 1 120); do curl -sf -o /dev/null http://127.0.0.1:8091/health && exit 0; sleep 0.5; done; exit 0'
TimeoutStartSec=90
Restart=on-failure
Nice=10
CPUWeight=20
IOWeight=20
UNIT
cat > /etc/systemd/system/llm-proxy.socket <<UNIT
[Unit]
Description=Hitradar KI – Eingang (startet die KI bei Bedarf)

[Socket]
ListenStream=127.0.0.1:8090

[Install]
WantedBy=sockets.target
UNIT
cat > /etc/systemd/system/llm-proxy.service <<UNIT
[Unit]
Description=Hitradar KI – Weiterleitung, geht nach 30 Minuten ohne Anfrage aus
Requires=llm.service llm-proxy.socket
After=llm.service

[Service]
ExecStart=$PROXYD --exit-idle-time=30min 127.0.0.1:8091
UNIT
systemctl daemon-reload
systemctl disable llm.service 2>/dev/null || true
systemctl stop llm.service 2>/dev/null || true
systemctl enable --now llm-proxy.socket
echo "KI läuft jetzt nur bei Bedarf"
