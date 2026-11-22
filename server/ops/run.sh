#!/bin/bash
sleep 60
curl -s https://130-61-78-232.sslip.io:8080/health; echo
curl -s https://130-61-78-232.sslip.io:8080/rising | head -c 400; echo
journalctl -u radar --since "-2 min" --no-pager | grep -iE "Traceback|Error" | tail -3
