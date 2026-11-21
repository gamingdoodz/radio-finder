#!/bin/bash
# Updater: Befehle (ops) starten den Radar-Server nicht mehr neu
sed -i "s#git diff --quiet \$OLD HEAD -- server/ \&\&#git diff --quiet \$OLD HEAD -- server/ ':!server/ops' \&\&#" /usr/local/bin/radar-update
grep -n "diff" /usr/local/bin/radar-update
sleep 45
for u in "http://localhost:8081/search?q=weihnachtsb%C3%A4ckerei" "https://130-61-78-232.sslip.io:8080/search?q=last%20christmas"; do
  echo "== $u"; curl -s -m 20 -o /tmp/r.json -w "HTTP %{http_code} in %{time_total}s, %{size_download} Bytes\n" "$u"; head -c 300 /tmp/r.json; echo
done
journalctl -u radar --since "-3 hours" --no-pager | grep -iE "Traceback|Error|Started|Stopping" | tail -12
