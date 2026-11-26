#!/bin/bash
# Hitradar sparsamer (26.11.): KI nur bei Bedarf – nur nach Update-Absprache mit dem Spiel
R=/root/radio-finder
AB="python3 $R/server/absprache.py"
if ! systemctl is-enabled -q llm-proxy.socket 2>/dev/null; then
  $AB melde update 1800
  if $AB warte 1500; then
    bash $R/server/llm-dienst.sh 2>&1 | tail -5
  else
    echo "KI-Umstellung: Update nicht erlaubt, später erneut"
  fi
  $AB melde leicht
fi
echo "== Dienste =="; systemctl is-active radar llm llm-proxy.socket 2>&1 | paste -sd' '
echo "== Meldung =="; head -c 600 /var/lib/serverabsprache/dienste/hitradar.json 2>&1; echo
echo "== Update-Freigabe =="; $AB darf 2>&1
ls -la /var/lib/hitradar-neustart-offen 2>&1
echo "== Speicher Hitradar =="; du -sh $R /opt/llama.cpp /opt/models 2>/dev/null
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-30 min" --no-pager | grep -iE "Serverabsprache|Traceback|Error" | tail -8
