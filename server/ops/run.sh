#!/bin/bash
# Nur lesen: Absprachen des Spiels (Valdrakor) zur Server-Leistung
V=/opt/valdrakor
echo "== hitradar-sparsam.yml =="; cat $V/.github/workflows/hitradar-sparsam.yml
echo "== README (Leistung/Hitradar) =="; grep -niE -B2 -A8 "hitradar|radar|leistung|cpu|ressourc|budget|prior" $V/README.md | head -120
echo "== RUNNER.md =="; grep -niE -B2 -A6 "hitradar|radar|leistung|cpu|ressourc|prior" $V/ops/RUNNER.md | head -60
echo "== weitere Absprache-Dateien =="; ls $V/ops; grep -rlIiE "hitradar|radar" $V/ops $V/docs 2>/dev/null
for f in $(grep -rlIiE "hitradar|radar\.service" $V/ops $V/docs $V/scripts 2>/dev/null | grep -vE "RUNNER.md|RESTORE.md"); do echo "== $f =="; grep -niE -B3 -A10 "hitradar|radar" $f | head -60; done
echo "== radar drop-ins =="; ls -la /etc/systemd/system/radar.service.d/; head -30 /etc/systemd/system/radar.service.d/*
echo "== Last-Dienst =="; systemctl cat valdrakor-last --no-pager | head -30
