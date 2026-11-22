#!/bin/bash
sleep 75
echo "== Mix-Stream 40 s:"
curl -s -m 40 -o /tmp/mix.mp3 -w "HTTP %{http_code}, %{size_download} Bytes, %{content_type}\n" "https://130-61-78-232.sslip.io:8080/mix?g="
file /tmp/mix.mp3 2>/dev/null | head -1
curl -s -m 30 -o /dev/null -w "Mix Weihnachten: HTTP %{http_code}, %{size_download} Bytes\n" "https://130-61-78-232.sslip.io:8080/mix?g=xmas"
journalctl -u radar --since "-3 min" --no-pager | grep -iE "Traceback|Error" | tail -5
