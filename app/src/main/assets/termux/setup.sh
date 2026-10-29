#!/data/data/com.termux/files/usr/bin/bash
# One-time: build llama.cpp (llama-server, multimodal/audio capable) inside Termux.
set -e
pkg update -y && pkg install -y git cmake clang make python curl libcurl
mkdir -p ~/llm/models ~/llm/kb
cd ~/llm
[ -d llama.cpp ] || git clone --depth 1 https://github.com/ggml-org/llama.cpp
cd llama.cpp
cmake -B build -DLLAMA_CURL=OFF -DGGML_NATIVE=ON
cmake --build build --config Release -j4 --target llama-server
echo "SETUP DONE"
