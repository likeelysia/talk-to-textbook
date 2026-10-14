@echo off
REM 先把工作目录挪到工具目录 —— 否则 embedding 窗口的“当前目录”就是项目目录,
REM 会把项目文件夹锁住(改名 / 删除时会报“另一个进程正在使用此文件”)。
cd /d "F:\opc-tools"
REM ============================================================
REM  Start local embedding service (llama.cpp + bge-m3)
REM  OpenAI-compatible endpoint: POST http://127.0.0.1:8090/v1/embeddings
REM  Stop: close the minimized window, or: taskkill /IM llama-server.exe /F
REM ============================================================
start "embedding" "F:\opc-tools\llama.cpp\llama-server.exe" -m "F:\opc-tools\llm-models\bge-m3-Q8_0.gguf" --embeddings --pooling cls -c 512 --host 127.0.0.1 --port 8090 -t 8
echo embedding service starting on http://127.0.0.1:8090
