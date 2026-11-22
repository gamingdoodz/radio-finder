#!/bin/bash
# Hitradar und KI mit niedrigerer Priorität: andere Dienste (z. B. das Spiel) bekommen zuerst Rechenzeit
for s in radar llm; do
  mkdir -p /etc/systemd/system/$s.service.d
  printf '[Service]\nNice=10\nCPUWeight=50\n' > /etc/systemd/system/$s.service.d/priority.conf
done
systemctl daemon-reload
systemctl restart llm
systemctl restart radar
sleep 20
ps -eo ni,comm | grep -E "python|llama" | head
curl -s http://localhost:8081/health; echo
