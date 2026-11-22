#!/bin/bash
for u in "/sync?from=0" "/now?limit=8000&music=1" "/now?limit=1500&music=1"; do
  curl -s -H "Accept-Encoding: gzip" -o /tmp/s.gz -w "$u: %{size_download} Bytes gzip, %{time_total}s\n" "https://130-61-78-232.sslip.io:8080$u"
done
gunzip -c /tmp/s.gz > /dev/null 2>&1
curl -s -H "Accept-Encoding: gzip" "https://130-61-78-232.sslip.io:8080/sync?from=0" | gunzip | wc -c | xargs echo "sync entpackt Bytes:"
