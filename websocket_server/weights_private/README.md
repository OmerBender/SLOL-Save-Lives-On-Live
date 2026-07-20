# Private Model Weights

The trained YOLO weights are not included in this repository.

Authorized users should place the approved model file in the server folder:

```text
websocket_server/best.pt
```

On a Google Cloud GPU VM, the PyTorch model can be exported to TensorRT and used as:

```text
websocket_server/best.engine
```

Do not commit `.pt`, `.engine`, `.onnx`, or exported model files to Git unless explicit permission is granted by the project owner.
