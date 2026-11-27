#!/bin/bash
# Morgen-Check 27.11.: Freigabe, Meldung, Hinweise, KI-Umstellung
R=/root/radio-finder
AB="python3 $R/server/absprache.py"
if ! systemctl is-enabled -q llm-proxy.socket 2>/dev/null && $AB bereit; then
  $AB melde update 1200
  if $AB warte 900; then
    bash $R/server/llm-dienst.sh 2>&1 | tail -5
  else
    echo "KI-Umstellung: Update nicht erlaubt, später erneut"
  fi
  $AB melde leicht
fi
echo "== Dienste =="; systemctl is-active radar llm llm-proxy.socket 2>&1 | paste -sd' '
echo "== Meldung =="; head -c 600 /var/lib/serverabsprache/dienste/hitradar.json 2>&1; echo
ls -ld /var/lib/serverabsprache/dienste 2>&1; ls -l /var/lib/serverabsprache/dienste/hitradar.json 2>&1
U=$(stat -c %U /proc/$(systemctl show -p MainPID --value radar) 2>/dev/null); echo "radar läuft als: $U"
[ -n "$U" ] && { sudo -u "$U" test -w /var/lib/serverabsprache/dienste && echo "radar darf in dienste/ schreiben" || echo "radar darf in dienste/ NICHT schreiben (kommt mit dem nächsten Valdrakor-Update)"; }
echo "== Hinweise vom Spiel =="; head -c 3000 /run/serverabsprache/hinweise/hitradar.json 2>&1; echo
echo "== Update-Freigabe =="; $AB darf 2>&1; ls -la /run/serverabsprache 2>&1
curl -s -m 5 http://127.0.0.1:8100/api/last | python3 -c 'import sys,json; d=json.load(sys.stdin); print("api/last Felder:", sorted(d)); print({k: d.get(k) for k in ("updateErlaubt","updateDran","stufe")})' 2>&1
ls -la /var/lib/hitradar-neustart-offen 2>&1
echo "== Speicher Hitradar =="; du -sh $R /opt/llama.cpp /opt/models 2>/dev/null
echo "== Hitradar =="; curl -s http://localhost:8081/health; echo
journalctl -u radar --since "-30 min" --no-pager | grep -iE "Serverabsprache|Traceback|Error" | tail -8
