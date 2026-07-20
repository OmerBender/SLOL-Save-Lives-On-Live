# SLOL Deployment

This document describes the deployment workflow represented by the repository and current project setup.

## Local Server Setup

From the repository root:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Place an authorized private model file in:

```text
websocket_server/best.pt
```

or, when using TensorRT on a compatible NVIDIA GPU environment:

```text
websocket_server/best.engine
```

Then run:

```bash
cd websocket_server
python server_websocket.py
```

Open the dashboard:

```text
http://127.0.0.1:8000/dashboard
```

## Demo Video Directory

Demo feeds are optional presentation inputs. Configure their directory with:

```bash
export RESCUE360_DEMO_VIDEO_DIR=<LOCAL_DEMO_VIDEO_DIR>
```

The server expects the configured demo videos to match the filenames defined in `server_websocket.py`.

## Google Cloud Notes

The current cloud deployment uses a Google Cloud GPU VM with a G2 machine family instance and an NVIDIA L4 GPU.

Verify CUDA availability from the active Python environment:

```bash
python -c "import torch; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'no cuda')"
```

The VM must allow inbound traffic on port `8000` for the dashboard and WebSocket server.

## systemd Service

The current VM can run the server as a Linux `systemd` service named `slol`.

Useful commands:

```bash
sudo systemctl status slol
sudo systemctl start slol
sudo systemctl stop slol
sudo systemctl restart slol
sudo journalctl -u slol -f
```

When the service is active, do not also run `python server_websocket.py` manually on the same port.

## Current VM Path Convention

The current VM deployment uses:

```text
~/websocket_server/server_websocket.py
```

and a Python environment similar to:

```text
~/rescue360-env
```

These are deployment paths, not public repository requirements.

## Production Limitations

The public repository does not include:

* Model weights
* Cloud credentials
* Insta360 credentials
* HTTPS/WSS reverse proxy configuration
* Production authentication

For production-style exposure, HTTPS/WSS termination and authentication should be added through infrastructure outside the current prototype server.
