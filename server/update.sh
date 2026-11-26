#!/bin/bash
# Neue Version holen und Server neu starten – nur nach Absprache mit Valdrakor (updateErlaubt)
sudo install -m 755 ~/radio-finder/server/radar-update.sh /usr/local/bin/radar-update && sudo /usr/local/bin/radar-update
