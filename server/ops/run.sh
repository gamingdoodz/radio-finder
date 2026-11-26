#!/bin/bash
# Nur anschauen: was läuft auf dem Server, gibt es Absprachen vom Spiel?
echo "== Rechner =="; nproc; free -m; uptime; df -h / | tail -1
echo "== Dienste =="; systemctl list-units --type=service --state=running --no-pager --no-legend | awk '{print $1}' | grep -vE "^(systemd|dbus|cron|ssh|snap|getty|polkit|rsyslog|udisks|multipathd|unattended|networkd|irqbalance|chrony|iscsid|ModemManager|serial-getty|user@)" 
echo "== Slices =="; systemctl list-units --type=slice --no-pager --no-legend | awk '{print $1}'
echo "== Top CPU/RAM =="; ps -eo pid,user,pcpu,pmem,rss,nice,comm --sort=-pcpu | head -15
echo "== Docker =="; (docker ps --format '{{.Names}} {{.Image}} {{.Status}}' 2>/dev/null || echo "kein docker")
echo "== Lauschende Ports =="; ss -tlnp | awk '{print $4, $6}' | tail -n +2
echo "== radar.service =="; systemctl cat radar --no-pager 2>/dev/null | grep -vE "^#|^$"
for u in $(systemctl list-units --type=service --state=running --no-legend | awk '{print $1}' | grep -iE "game|spiel|drachen|valley|mmo|world|server" ); do echo "== $u =="; systemctl cat $u --no-pager | grep -vE "^$"; done
echo "== Absprache-Dateien =="
for d in /etc /opt /srv /var/lib /home /root; do
  grep -rlIsiE "radar|hitradar|radio-finder" $d --include=*.md --include=*.txt --include=*.json --include=*.yml --include=*.yaml --include=*.toml --include=*.conf --include=*.env --include=*.ini 2>/dev/null | grep -vE "radio-finder/(app|server)/|/venv/|node_modules|\.git/" | head -20
done
ls -la /etc/ressourcen* /etc/leistung* /etc/server-* /opt/*/RESSOURCEN* /opt/*/README* /srv/* /home/*/README* /root/*.md 2>/dev/null
ls /opt /srv /home 2>/dev/null
echo "== Crontab =="; crontab -l 2>/dev/null; ls /etc/cron.d
