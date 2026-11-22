#!/bin/bash
# Caddy: Streams sofort durchreichen (fürs Casten), kein gzip auf Audio
D=$(grep -o '^[0-9-]*\.sslip\.io:8080' /etc/caddy/Caddyfile)
cat > /etc/caddy/Caddyfile <<CADDY
http://:80 {
    reverse_proxy localhost:8081 {
        flush_interval -1
    }
}
$D {
    @notrelay not path /relay
    encode @notrelay gzip
    reverse_proxy localhost:8081 {
        flush_interval -1
    }
}
CADDY
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | tail -2 && systemctl reload caddy
sleep 50
curl -s https://$D/health; echo
ID=$(curl -s "http://localhost:8081/list?t=popular" --compressed | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
echo "Test-Sender: $ID"
curl -s -m 8 -o /tmp/relay.bin -w "Relay: HTTP %{http_code}, %{size_download} Bytes, %{content_type}\n" "https://$D/relay?id=$ID"
curl -s -m 10 "https://$D/cover?q=Wham%20-%20Last%20Christmas"; echo
curl -s -m 10 "https://$D/popular" | head -c 200; echo
journalctl -u radar --since "-1 hours" --no-pager | grep -iE "Traceback|Error" | tail -5
