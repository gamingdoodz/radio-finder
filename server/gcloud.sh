#!/bin/bash
# Legt in Google Cloud den kostenlosen Server an und richtet den Radar-Server ein.
# In der Cloud Shell aufrufen:
#   curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/gcloud.sh | bash
set -e
ZONE=us-central1-a

PROJECT=$(gcloud config get-value project 2>/dev/null)
if [ -z "$PROJECT" ]; then
  PROJECT=$(gcloud projects list --format="value(projectId)" --limit=1)
  gcloud config set project "$PROJECT"
fi
echo "Projekt: $PROJECT"

echo "Compute Engine wird aktiviert (kann 1-2 Minuten dauern)…"
gcloud services enable compute.googleapis.com

cat > /tmp/radar-start.sh <<'EOF'
#!/bin/bash
[ -f /var/radar-ok ] && exit 0
export HOME=/root USER=root
curl -fsSL https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/setup.sh | bash && touch /var/radar-ok
EOF

if ! gcloud compute instances describe radar --zone=$ZONE >/dev/null 2>&1; then
  echo "Server wird angelegt (e2-micro, kostenlos)…"
  gcloud compute instances create radar \
    --zone=$ZONE \
    --machine-type=e2-micro \
    --image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud \
    --boot-disk-size=30GB --boot-disk-type=pd-standard \
    --tags=http-server \
    --metadata-from-file=startup-script=/tmp/radar-start.sh
fi

gcloud compute firewall-rules describe allow-radar-http >/dev/null 2>&1 || \
  gcloud compute firewall-rules create allow-radar-http --allow=tcp:80,tcp:8080 --target-tags=http-server

IP=$(gcloud compute instances describe radar --zone=$ZONE --format="value(networkInterfaces[0].accessConfigs[0].natIP)")
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
echo "Noch nicht erreichbar – in ein paar Minuten testen: http://$IP/health"
echo "Server-Adresse: http://$IP"
