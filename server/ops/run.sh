#!/bin/bash
sleep 90
curl -s https://130-61-78-232.sslip.io:8080/health; echo
curl -s "http://localhost:8081/now?limit=3" ; echo
journalctl -u radar --since "-2 min" --no-pager | grep -iE "Traceback|Error" | tail -3
