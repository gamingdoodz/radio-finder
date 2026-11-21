#!/bin/bash
# Kleine KI für "Wünsch dir was": llama.cpp + Qwen2.5 1.5B (läuft nur auf dem Server, kostenlos).
set -e
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q build-essential cmake git curl
if [ ! -x /opt/llama.cpp/build/bin/llama-server ]; then
  rm -rf /opt/llama.cpp
  git clone -q --depth 1 https://github.com/ggml-org/llama.cpp /opt/llama.cpp
  cd /opt/llama.cpp
  cmake -B build -DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF >/dev/null
  cmake --build build -j2 --target llama-server >/dev/null
fi
mkdir -p /opt/models
if [ ! -s /opt/models/qwen.gguf ]; then
  curl -sL -o /opt/models/qwen.gguf.part \
    https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
  mv /opt/models/qwen.gguf.part /opt/models/qwen.gguf
fi
cat > /etc/systemd/system/llm.service <<UNIT
[Unit]
Description=Hitradar KI (Wünsch dir was)
After=network-online.target

[Service]
ExecStart=/opt/llama.cpp/build/bin/llama-server -m /opt/models/qwen.gguf --host 127.0.0.1 --port 8090 -c 2048 -t 2 --parallel 1
Restart=always
Nice=10

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now llm
systemctl restart llm
echo "KI eingerichtet"
