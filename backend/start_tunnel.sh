#!/usr/bin/env bash
# start_tunnel.sh — Exposes local SatarkAI backend (:8000) over a secure public HTTPS URL
# Works on any Wi-Fi network and 4G/5G mobile data worldwide without port forwarding.

PORT=${PORT:-8000}

echo "=========================================================="
echo " Starting SatarkAI Universal Tunnel on port $PORT"
echo "=========================================================="

if command -v cloudflared &> /dev/null; then
    echo "Using cloudflared tunnel..."
    cloudflared tunnel --url http://127.0.0.1:$PORT
elif command -v npx &> /dev/null; then
    echo "cloudflared not found in PATH. Using localtunnel via npx..."
    npx localtunnel --port $PORT
else
    echo "Neither 'cloudflared' nor 'npx' was found in PATH."
    echo "To get a free public tunnel, install cloudflared:"
    echo "  curl -L --output cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
    echo "  chmod +x cloudflared && sudo mv cloudflared /usr/local/bin/"
    exit 1
fi
