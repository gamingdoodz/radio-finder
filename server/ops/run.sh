#!/bin/bash
# Alexa: Port 443 (Alexa verlangt https auf 443), Prüfbibliothek, Test
D=$(grep -o '^[0-9-]*\.sslip\.io' /etc/caddy/Caddyfile | head -1)
iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT
netfilter-persistent save >/dev/null 2>&1 || true
cat > /etc/caddy/Caddyfile <<CADDY
http://:80 {
    reverse_proxy localhost:8081 {
        flush_interval -1
    }
}
$D:8080, $D {
    @notrelay not path /relay
    encode @notrelay gzip
    reverse_proxy localhost:8081 {
        flush_interval -1
    }
}
CADDY
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | tail -1 && systemctl reload caddy
/root/radio-finder/server/venv/bin/pip install -q cryptography 2>&1 | tail -2
/root/radio-finder/server/venv/bin/python -c "import cryptography; from cryptography.x509.verification import PolicyBuilder; print('cryptography', cryptography.__version__)"
systemctl restart radar
sleep 60
echo "== 443 von innen:"; curl -s -m 10 https://$D/health; echo
echo "== Alexa ohne Signatur (muss 400 sein):"; curl -s -o /dev/null -w "%{http_code}\n" -X POST -d '{}' https://$D:8080/alexa
echo "== Alexa-Test mit echten Daten (Signatur hier übersprungen):"
cd /root/radio-finder/server && timeout 120 ./venv/bin/python - <<'PY'
import asyncio, json, time, aiohttp, radar
async def t():
    async with aiohttp.ClientSession() as s:
        async with s.get("http://localhost:8081/now?limit=5000") as r:
            data = await r.json(content_type=None)
    items = data if isinstance(data, list) else data.get("items", data.get("now", []))
    print("live:", len(items))
    for it in items:
        radar.stations.append({"id": it["id"], "name": it["name"], "url": it["url"], "favicon": "", "tags": ""})
        radar.now[it["id"]] = {"title": it["title"], "norm": radar.normalize(it["title"]), "since": it.get("since", time.time()), "time": time.time()}
    radar.SHARED["session"] = s
    async def ok(req, body): return True
    radar.alexa_verify = ok
    class Req:
        def __init__(self, b): self.b = b; self.headers = {}
        async def read(self): return self.b
    for intent, slots in [("LaunchRequest", None), ("PlaySongIntent", {"song": {"value": "last christmas"}}),
                          ("HitMixIntent", {"genre": {"value": "rock"}}), ("AMAZON.NextIntent", {}), ("AMAZON.PauseIntent", {})]:
        r = {"type": intent} if slots is None else {"type": "IntentRequest", "intent": {"name": intent, "slots": slots}}
        body = json.dumps({"request": r, "context": {"AudioPlayer": {"token": "mix||x"}}}).encode()
        resp = await radar.h_alexa(Req(body))
        print(intent, "->", resp.text[:260])
asyncio.run(t())
PY
journalctl -u radar --since "-3 min" --no-pager | grep -iE "Traceback|Error" | tail -5
