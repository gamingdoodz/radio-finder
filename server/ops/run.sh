#!/bin/bash
journalctl -u radar --since "-4 hours" --no-pager | grep -A14 "Traceback" | tail -45
