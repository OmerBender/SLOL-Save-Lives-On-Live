# Private Model Weights

Model weights are intentionally not included in this public repository.

Authorized users should place approved model files directly in:

```text
websocket_server/
```

Supported runtime filenames:

```text
best.pt
best.engine
```

Runtime behavior in `server_websocket.py`:

1. Use `best.engine` when it exists.
2. Otherwise fall back to `best.pt`.

Do not commit `.pt`, `.engine`, `.onnx`, TensorRT exports, or other model-weight files.
