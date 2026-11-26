#!/usr/bin/env python3
"""Filter für Protokolle: ersetzt alles, was nach Passwort, Token oder Schlüssel aussieht, durch ***.
Gibt den Text sonst unverändert weiter (zeilenweise, damit lange Ausgaben flüssig bleiben)."""
import re
import sys

NAME = r"[A-Za-z0-9_.-]*(?:pass(?:word|wd)?|secret|token|api[_-]?key|private[_-]?key|access[_-]?key|lw_key|_key|auth|credential|cookie|session)[A-Za-z0-9_.-]*"
RULES = [
    # NAME=wert, NAME: wert, "name": "wert"
    (re.compile(r'(?i)(["\']?' + NAME + r'["\']?\s*[=:]\s*["\']?)(?!\*\*\*)([^\s"\',;}]{4,})'), r"\1***"),
    (re.compile(r"(?i)(authorization:\s*(?:bearer|basic|token)\s+)\S+"), r"\1***"),
    (re.compile(r"(https?://[^/\s:@]+:)[^/\s@]+@"), r"\1***@"),
    (re.compile(r"(ghp_|gho_|ghs_|ghu_|github_pat_|hf_|sk-|xox[bpa]-|AKIA|AIza|ya29\.)[A-Za-z0-9_\-.]{8,}"), r"\1***"),
    # lange Hex-/Base64-Werte (Commit-Kennungen mit genau 40 Zeichen bleiben lesbar)
    (re.compile(r"(?<![0-9A-Za-z])(?![0-9a-f]{40}(?![0-9A-Za-z]))[0-9a-fA-F]{32,}(?![0-9A-Za-z])"), "***"),
    (re.compile(r"(?<![A-Za-z0-9+/])(?=[A-Za-z0-9+/]*[0-9])(?=[A-Za-z0-9+/]*[A-Z])(?=[A-Za-z0-9+/]*[a-z])[A-Za-z0-9+/]{40,}={0,2}"), "***"),
]
in_key = False
for line in sys.stdin:
    if "PRIVATE KEY-----" in line:
        in_key = "BEGIN" in line
        print("*** (privater Schlüssel ausgeblendet) ***", flush=True)
        continue
    if in_key:
        continue
    for rx, rep in RULES:
        line = rx.sub(rep, line)
    sys.stdout.write(line)
    sys.stdout.flush()
