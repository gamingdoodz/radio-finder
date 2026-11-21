#!/bin/bash
# Suche testen
for u in "http://localhost:8081/search?q=weihnachtsb%C3%A4ckerei" "http://localhost/search?q=weihnachtsb%C3%A4ckerei" "https://130-61-78-232.sslip.io:8080/search?q=weihnachtsb%C3%A4ckerei"; do
  echo "== $u"; curl -s -m 20 -o /tmp/r.json -w "HTTP %{http_code} in %{time_total}s, %{size_download} Bytes\n" "$u"; head -c 300 /tmp/r.json; echo
done
journalctl -u radar -n 20 --no-pager | grep -iE "error|exception|traceback" | tail -5
uptime
