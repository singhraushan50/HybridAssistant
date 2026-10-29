#!/data/data/com.termux/files/usr/bin/bash
# Starts an OpenAI-compatible server on 127.0.0.1:8080 using whatever GGUF is in ~/llm/models.
cd ~/llm
M=$(ls models/*.gguf 2>/dev/null | grep -vi mmproj | head -1)
P=$(ls models/*mmproj*.gguf 2>/dev/null | head -1)
[ -z "$M" ] && { echo "No model .gguf in ~/llm/models"; exit 1; }
pkill -f llama-server 2>/dev/null
ARGS="-m $M --host 127.0.0.1 --port 8080 -c 4096 -t 4 --jinja"
[ -n "$P" ] && ARGS="$ARGS --mmproj $P"
nohup llama.cpp/build/bin/llama-server $ARGS > server.log 2>&1 &
echo "started: $M ${P:+(+ $P)}"

