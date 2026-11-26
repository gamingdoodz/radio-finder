#!/bin/bash
# Prüfregel: Skripte, die auf dem Server laufen, und Workflows dürfen keine Geheimnisse ausgeben.
# Verboten: set -x / bash -x, Umgebung ausgeben (env, printenv, export -p, declare -x, /proc/*/environ),
# Dienst-Definitionen samt Umgebung (systemctl cat, systemctl show ... Environment), .env-Dateien anzeigen,
# docker inspect / docker compose config, Secrets direkt per echo.
# Ausnahme für eine Zeile: Kommentar "# geheimnis-ok: <Grund>" am Zeilenende.
set -u
FILES=$(ls server/ops/*.sh server/*.sh .github/workflows/*.yml 2>/dev/null)
RULES=(
  'set -[a-z]*x|set -o xtrace|bash -[a-z]*x\b|sh -x\b'
  '(^|[;&|(`[:space:]])(printenv|export -p|declare -[a-z]*x|env)([[:space:]]*($|[|;&>)]))'
  '/proc/[^ ]*/environ'
  'systemctl[[:space:]]+cat\b'
  'systemctl[[:space:]]+show\b.*Environment'
  '(cat|head|tail|less|more|grep|sed|awk)[^|;]*\.env\b'
  'docker[[:space:]]+(inspect|compose[[:space:]]+config)\b'
  'echo[^#]*\$\{\{[[:space:]]*secrets\.'
  'echo[^#]*\$(KEY_B64|[A-Z_]*(PASSWORD|SECRET|TOKEN|_KEY))\b'
)
bad=0
for f in $FILES; do
  for r in "${RULES[@]}"; do
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      case "$hit" in *"geheimnis-ok:"*) continue ;; esac
      # Kommentarzeilen zählen nicht
      code=${hit#*:}; code=${code#*:}
      case "$(echo "$code" | sed 's/^[[:space:]]*//')" in '#'*) continue ;; esac
      echo "VERBOTEN in $hit"; bad=1
    done < <(grep -nE "$r" "$f" 2>/dev/null | sed "s|^|$f:|")
  done
done
[ "$bad" = 0 ] && echo "Prüfregel Geheimnisse: alles in Ordnung" || { echo "Prüfregel Geheimnisse verletzt (siehe oben)"; exit 1; }
