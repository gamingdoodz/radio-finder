#!/bin/bash
# HTTPS ohne zusätzliche Ports: Caddy übernimmt Port 80 (normal + Zertifikat) und 8080 (verschlüsselt).
# Der Radar-Server selbst läuft dahinter auf Port 8081.
#   https://<IP-mit-Bindestrichen>.sslip.io:8080   (sslip.io leitet auf die IP weiter)
set -e
IP=$(curl -s https://api.ipify.org)
DOMAIN="$(echo "$IP" | tr . -).sslip.io"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q caddy
# Radar auf 8081 umziehen
mkdir -p /etc/systemd/system/radar.service.d
printf '[Service]\nEnvironment=PORT=8081\n' > /etc/systemd/system/radar.service.d/port.conf
systemctl daemon-reload
systemctl restart radar
# Alte Umleitung 80 -> 8080 entfernen, Port 80 direkt erlauben
while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080 2>/dev/null; do :; done
iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT
netfilter-persistent save >/dev/null 2>&1 || true
cat > /etc/caddy/Caddyfile <<CADDY
http://:80 {
    reverse_proxy localhost:8081
}
$DOMAIN:8080 {
    encode gzip
    reverse_proxy localhost:8081
}
CADDY
systemctl enable caddy >/dev/null 2>&1
systemctl restart caddy
echo "Warte auf Zertifikat…"
for i in $(seq 1 40); do
  if curl -s -m 5 "https://$DOMAIN:8080/health" >/dev/null; then
    echo "HTTPS läuft: https://$DOMAIN:8080"
    curl -s "https://$DOMAIN:8080/health"; echo
    exit 0
  fi
  sleep 5
done
echo "Noch kein Zertifikat:"
journalctl -u caddy -n 20 --no-pager
