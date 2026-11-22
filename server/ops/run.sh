#!/bin/bash
free -m | head -2
nproc
uptime
ps -eo rss,comm --sort=-rss | head -6
