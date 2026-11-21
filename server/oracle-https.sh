#!/bin/bash
# In der Oracle Cloud Shell starten: öffnet Port 443 und richtet HTTPS auf dem Server ein.
#   curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/oracle-https.sh | bash
set -e
T="${OCI_TENANCY:?Bitte in der Oracle Cloud Shell starten}"
VCN=$(oci network vcn list -c "$T" --display-name radar-vcn --query 'data[0].id' --raw-output)
SL=$(oci network vcn get --vcn-id "$VCN" --query 'data."default-security-list-id"' --raw-output)
RULES=$(oci network security-list get --security-list-id "$SL" --query 'data."ingress-security-rules"')
if echo "$RULES" | grep -q '"min": 443'; then
  echo "Port 443 ist schon offen."
else
  echo "Öffne Port 443…"
  NEW=$(echo "$RULES" | python3 -c 'import json,sys; r=json.load(sys.stdin); r.append({"source":"0.0.0.0/0","protocol":"6","isStateless":False,"tcpOptions":{"destinationPortRange":{"min":443,"max":443}}}); print(json.dumps(r))')
  oci network security-list update --security-list-id "$SL" --ingress-security-rules "$NEW" --force >/dev/null
fi
ID=$(oci compute instance list -c "$T" --display-name radar --lifecycle-state RUNNING --query 'data[0].id' --raw-output)
IP=$(oci compute instance list-vnics --instance-id "$ID" --query 'data[0]."public-ip"' --raw-output)
ssh -o StrictHostKeyChecking=no ubuntu@"$IP" "curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/https.sh | sudo bash"
