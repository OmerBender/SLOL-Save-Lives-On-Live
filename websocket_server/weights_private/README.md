# Private Model Weights

Model weights are intentionally not included in this public repository.

This `weights_private/` folder contains documentation and handoff instructions only. It is not the runtime model directory.

The current server code loads model files directly from:

```text
websocket_server/best.engine
websocket_server/best.pt
```

Runtime behavior in `server_websocket.py`:

1. `best.engine` is preferred when it exists directly in `websocket_server/`.
2. `best.pt` is used as the fallback when `best.engine` is not present.

Authorized users should place approved model files directly in `websocket_server/`.

Do not commit `.pt`, `.engine`, `.onnx`, TensorRT exports, or other model-weight files.
