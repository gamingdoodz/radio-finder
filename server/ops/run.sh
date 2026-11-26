#!/bin/bash
# Nur lesen: neue Serverabsprache von Valdrakor (Format von lage.json und Anmeldungen)
echo "== /run/serverabsprache =="; ls -la /run/serverabsprache 2>&1
echo "== lage.json =="; head -c 3000 /run/serverabsprache/lage.json 2>&1; echo
echo "== /var/lib/serverabsprache =="; ls -la /var/lib/serverabsprache 2>&1
for f in /var/lib/serverabsprache/*; do [ -f "$f" ] && { echo "-- $f"; head -c 1500 "$f"; echo; }; done
echo "== Beschreibung im Spiel =="; for f in /opt/valdrakor/ops/*absprache* /opt/valdrakor/docs/*absprache* /opt/valdrakor/docs/*ABSPRACHE*; do [ -f "$f" ] && { echo "-- $f"; head -120 "$f"; }; done
grep -rlI "serverabsprache" /opt/valdrakor/ops /opt/valdrakor/docs /opt/valdrakor/README.md 2>/dev/null | head
