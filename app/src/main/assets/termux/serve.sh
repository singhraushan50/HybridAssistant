#!/data/data/com.termux/files/usr/bin/bash
# Runs llama-server in the foreground of a Termux session (used by the app's Start button).
cd ~/llm
termux-wake-lock 2>/dev/null
M=$(ls models/*.gguf 2>/dev/null | grep -vi mmproj | head -1)
P=$(ls models/*mmproj*.gguf 2>/dev/null | head -1)
if [ -z "$M" ]; then echo "No model .gguf in ~/llm/models" > server.log; exit 1; fi
pkill -f "build/bin/llama-server" 2>/dev/null
ARGS="-m $M --host 127.0.0.1 --port 8080 -c 4096 -t 4 --jinja --temp 0.3 --top-k 50 --repeat-penalty 1.1"
[ -n "$P" ] && ARGS="$ARGS --mmproj $P"
exec llama.cpp/build/bin/llama-server $ARGS > server.log 2>&1
