#!/bin/bash
# Neue Version holen und Server neu starten
cd ~/radio-finder && git pull --ff-only && cd server && ./venv/bin/pip install -q -r requirements.txt && sudo systemctl restart radar
