#!/bin/bash
sleep 30
curl -s https://130-61-78-232.sslip.io:8080/rising | head -c 600; echo
