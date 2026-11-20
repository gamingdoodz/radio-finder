#!/bin/bash
# Einmalige Einrichtung auf dem Oracle-Server (Ubuntu). Aufruf: bash setup.sh
set -e
sudo apt-get update
sudo apt-get install -y python3-venv python3-pip git
cd ~
[ -d radio-finder ] || git clone https://github.com/gamingdoodz/radio-finder.git
cd radio-finder/server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt

# Als Dienst einrichten: startet automatisch und nach Absturz neu
sudo tee /etc/systemd/system/radar.service > /dev/null <<UNIT
[Unit]
Description=Radar-Server
After=network-online.target

[Service]
User=$USER
WorkingDirectory=$HOME/radio-finder/server
ExecStart=$HOME/radio-finder/server/venv/bin/python radar.py
Restart=always
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now radar

# Port 8080 in der Server-Firewall öffnen
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
sudo netfilter-persistent save 2>/dev/null || sudo apt-get install -y iptables-persistent
echo "Fertig! Test: curl http://localhost:8080/health"
