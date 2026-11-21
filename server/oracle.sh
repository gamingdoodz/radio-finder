#!/bin/bash
# Legt in Oracle Cloud den kostenlosen ARM-Server an (4 Kerne, 24 GB) und richtet den Radar-Server ein.
# In der Oracle Cloud Shell aufrufen:
#   curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/oracle.sh | bash
set -e
T="${OCI_TENANCY:?Bitte in der Oracle Cloud Shell starten}"
echo "Region: ${OCI_REGION:-?}"

q() { oci "$@" 2>/dev/null; }

# ---------- Netzwerk (nur beim ersten Mal) ----------
VCN=$(q network vcn list -c "$T" --display-name radar-vcn --query 'data[0].id' --raw-output)
if [ -z "$VCN" ] || [ "$VCN" = "null" ]; then
  echo "Netzwerk wird angelegt…"
  VCN=$(oci network vcn create -c "$T" --display-name radar-vcn --cidr-blocks '["10.0.0.0/16"]' \
    --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  IGW=$(oci network internet-gateway create -c "$T" --vcn-id "$VCN" --display-name radar-igw --is-enabled true \
    --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  RT=$(oci network vcn get --vcn-id "$VCN" --query 'data."default-route-table-id"' --raw-output)
  oci network route-table update --rt-id "$RT" --force \
    --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW\"}]" >/dev/null
  SL=$(oci network vcn get --vcn-id "$VCN" --query 'data."default-security-list-id"' --raw-output)
  oci network security-list update --security-list-id "$SL" --force \
    --egress-security-rules '[{"destination":"0.0.0.0/0","protocol":"all"}]' \
    --ingress-security-rules '[
      {"source":"0.0.0.0/0","protocol":"6","tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},
      {"source":"0.0.0.0/0","protocol":"6","tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},
      {"source":"0.0.0.0/0","protocol":"6","tcpOptions":{"destinationPortRange":{"min":8080,"max":8080}}}
    ]' >/dev/null
  oci network subnet create -c "$T" --vcn-id "$VCN" --display-name radar-subnet --cidr-block 10.0.0.0/24 \
    --wait-for-state AVAILABLE >/dev/null
fi
SUB=$(oci network subnet list -c "$T" --vcn-id "$VCN" --display-name radar-subnet --query 'data[0].id' --raw-output)

# ---------- Server ----------
ID=$(q compute instance list -c "$T" --display-name radar --lifecycle-state RUNNING --query 'data[0].id' --raw-output)
if [ -z "$ID" ] || [ "$ID" = "null" ]; then
  IMG=$(oci compute image list -c "$T" --operating-system "Canonical Ubuntu" --operating-system-version "24.04" \
    --shape VM.Standard.A1.Flex --sort-by TIMECREATED --sort-order DESC --limit 1 --query 'data[0].id' --raw-output)
  [ -f ~/.ssh/id_rsa.pub ] || ssh-keygen -t rsa -b 4096 -N "" -f ~/.ssh/id_rsa -q
  cat > /tmp/radar-init.sh <<'EOF'
#!/bin/bash
export HOME=/root USER=root
curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/setup.sh | bash
EOF
  ADS=$(oci iam availability-domain list -c "$T" --query 'data[].name' --raw-output | tr -d '[]", ' | grep -v '^$')
  echo "Server wird angelegt (4 Kerne, 24 GB, kostenlos)…"
  for try in $(seq 1 40); do
    for AD in $ADS; do
      OUT=$(oci compute instance launch -c "$T" --availability-domain "$AD" --display-name radar \
        --shape VM.Standard.A1.Flex --shape-config '{"ocpus":4,"memoryInGBs":24}' \
        --image-id "$IMG" --subnet-id "$SUB" --assign-public-ip true --boot-volume-size-in-gbs 50 \
        --ssh-authorized-keys-file ~/.ssh/id_rsa.pub --user-data-file /tmp/radar-init.sh \
        --wait-for-state RUNNING --query 'data.id' --raw-output 2>&1) && { ID="$OUT"; break 2; }
      if echo "$OUT" | grep -qi "capacity"; then
        echo "  $AD: gerade ausverkauft"
      else
        echo "$OUT" | tail -5; exit 1
      fi
    done
    echo "Versuch $try/40 – neuer Versuch in 60 Sekunden (Fenster offen lassen)…"
    sleep 60
  done
  if [ -z "$ID" ]; then
    echo "Oracle hat gerade keine freien Gratis-Server. Bitte später (z. B. nachts oder früh morgens) nochmal starten."
    exit 1
  fi
fi

IP=$(oci compute instance list-vnics --instance-id "$ID" --query 'data[0]."public-ip"' --raw-output)
echo
echo "Server läuft an, Einrichtung dauert ca. 5 Minuten…"
for i in $(seq 1 60); do
  if curl -s -m 5 "http://$IP/health" >/dev/null; then
    echo
    echo "=============================================="
    echo " FERTIG! Server-Adresse: http://$IP"
    echo "=============================================="
    curl -s "http://$IP/health"; echo
    exit 0
  fi
  printf "."
  sleep 10
done
echo
echo "Server-Adresse: http://$IP  (in ein paar Minuten testen: http://$IP/health)"
