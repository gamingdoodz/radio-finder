#!/bin/bash
# Einmalige Einrichtung auf einem Ubuntu-Server (Google Cloud e2-micro, Oracle usw.).
# Aufruf: curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/setup.sh | bash
set -e
sudo apt-get update
sudo apt-get install -y python3-venv python3-pip git

# Kleiner Server (1 GB RAM): 2 GB Auslagerung als Reserve
if [ ! -f /swapfile ]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
  sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# Bei wenig Arbeitsspeicher weniger gleichzeitig abfragen
MEM=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if [ "$MEM" -lt 2000 ]; then CONC=120; MAXS=8000
elif [ "$MEM" -lt 8000 ]; then CONC=250; MAXS=15000
else CONC=350; MAXS=60000; fi   # großer Server (z. B. Oracle): alle Sender

cd ~
if [ -d radio-finder ]; then (cd radio-finder && git pull); else git clone https://github.com/gamingdoodz/radio-finder.git; fi
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
Environment=CONCURRENCY=$CONC
Environment=MAX_STATIONS=$MAXS
ExecStart=$HOME/radio-finder/server/venv/bin/python radar.py
Restart=always
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now radar
sudo systemctl restart radar

# Port 80 (in Google Cloud mit "HTTP zulassen" offen) auf 8080 umleiten, 8080 auch direkt erlauben
sudo iptables -t nat -C PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080 2>/dev/null || \
  sudo iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080
sudo iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || \
  sudo iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT
echo iptables-persistent iptables-persistent/autosave_v4 boolean true | sudo debconf-set-selections
echo iptables-persistent iptables-persistent/autosave_v6 boolean true | sudo debconf-set-selections
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
sudo netfilter-persistent save

# Automatische Updates: stündlich neuen Server-Code holen und bei Änderung neu starten
sudo tee /usr/local/bin/radar-update > /dev/null <<UPD
#!/bin/bash
cd $HOME/radio-finder || exit 0
OLD=\$(git rev-parse HEAD)
git pull -q || exit 0
[ "\$OLD" != "\$(git rev-parse HEAD)" ] && ! git diff --quiet \$OLD HEAD -- server/ ':!server/ops' && systemctl restart radar
exit 0
UPD
sudo chmod +x /usr/local/bin/radar-update
echo "17 * * * * root /usr/local/bin/radar-update" | sudo tee /etc/cron.d/radar-update > /dev/null

sleep 5
echo
echo "=============================================="
curl -s http://localhost:8080/health && echo
echo "Fertig! Der Server läuft. Externe IP:"
curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip 2>/dev/null || curl -s https://api.ipify.org
echo
echo "=============================================="
