#!/bin/bash
# Auf dem Server: HTTPS mit kostenlosem Zertifikat (Let's Encrypt über Caddy).
# Adresse: https://<IP-mit-Bindestrichen>.sslip.io  (sslip.io leitet auf die IP weiter)
set -e
IP=$(curl -s https://api.ipify.org)
DOMAIN="$(echo "$IP" | tr . -).sslip.io"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q caddy
cat > /etc/caddy/Caddyfile <<CADDY
$DOMAIN {
    encode gzip
    reverse_proxy localhost:8080
}
CADDY
iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT
netfilter-persistent save >/dev/null 2>&1 || true
systemctl enable caddy >/dev/null 2>&1
systemctl restart caddy
echo "Warte auf Zertifikat…"
for i in $(seq 1 30); do
  if curl -s -m 5 "https://$DOMAIN/health" >/dev/null; then
    echo "HTTPS läuft: https://$DOMAIN"
    exit 0
  fi
  sleep 4
done
echo "Zertifikat noch nicht da – in ein paar Minuten testen: https://$DOMAIN/health"
