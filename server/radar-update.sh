#!/bin/bash
# Neuen Server-Code holen und radar nur nach Absprache mit Valdrakor neu starten.
# Wird nach /usr/local/bin/radar-update kopiert (stündlich per cron und vom Workflow "Server aktualisieren").
# Aufruf: radar-update [Stand vor dem Holen]   (der Workflow holt selbst und gibt den alten Stand mit)
# Regel: vorher "jetzt":"update" (bis höchstens 1 h), erst bei updateErlaubt true und updateDran nicht "spiel",
# danach sofort "leicht". Klappt es nicht, bleibt der Neustart vorgemerkt und der nächste Lauf versucht es wieder.
REPO=${RADAR_REPO:-/root/radio-finder}
OFFEN=/var/lib/hitradar-neustart-offen
AB="python3 $REPO/server/absprache.py"
cd "$REPO" || exit 0

# Immer nur ein Lauf gleichzeitig (cron und Workflow)
exec 9>/run/radar-update.lock
flock -n 9 || { echo "radar-update läuft schon"; exit 0; }

OLD=${1:-$(git rev-parse HEAD)}
git pull -q || true
if [ "$OLD" != "$(git rev-parse HEAD)" ] && ! git diff --quiet "$OLD" HEAD -- server/ ':!server/ops'; then
  touch "$OFFEN"
  # Neue Fassung von sich selbst übernehmen
  install -m 755 server/radar-update.sh /usr/local/bin/radar-update
fi
[ -f "$OFFEN" ] || { echo "Kein Neustart nötig"; exit 0; }

$AB melde update 1800
if $AB warte 1500; then
  $AB melde update 600
  ./server/venv/bin/pip install -q -r server/requirements.txt 2>/dev/null || true
  systemctl restart radar
  rm -f "$OFFEN"
  $AB melde leicht
  echo "radar neu gestartet"
else
  $AB melde leicht
  echo "Update nicht erlaubt, Neustart bleibt vorgemerkt (nächster Versuch beim nächsten Lauf)"
fi
exit 0
