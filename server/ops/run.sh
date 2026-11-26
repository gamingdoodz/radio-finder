#!/bin/bash
# Nur lesen (keine Geheimnisse ausgeben): Absprache des Spiels zu Hitradar
V=/opt/valdrakor
echo "== hitradar-sparsam.yml =="; grep -viE "key|pass|secret|token" $V/.github/workflows/hitradar-sparsam.yml | head -80
echo "== README (Hitradar) =="; grep -niE -A4 "hitradar" $V/README.md | grep -viE "key|pass|secret|token" | head -50
echo "== /api/last =="; curl -s -m 5 http://127.0.0.1:8100/api/last | head -c 600; echo
echo "== llm.service =="; systemctl show -p CPUWeight -p Nice -p MemoryHigh -p CPUQuotaPerSecUSec llm.service; ls /etc/systemd/system/llm.service.d 2>/dev/null
echo "== radar jetzt =="; systemctl show -p CPUWeight -p Nice -p CPUQuotaPerSecUSec radar.service
