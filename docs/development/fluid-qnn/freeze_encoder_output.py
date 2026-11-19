#!/usr/bin/env python3
"""Freeze encoder output metadata from ORT's static shape inference."""
from pathlib import Path
import os
import onnx
import onnxruntime as ort

p = Path('encoder.onnx')
model = onnx.load(str(p), load_external_data=False)
so = ort.SessionOptions()
so.inter_op_num_threads = 1
so.intra_op_num_threads = 1
s = ort.InferenceSession(str(p), sess_options=so, providers=['CPUExecutionProvider'])
output = s.get_outputs()[0]
expected_frames = int(os.environ.get('EXPECTED_OUTPUT_FRAMES', '125'))
assert output.name == 'encoder_out' and output.shape == [1, 1024, expected_frames], output.shape
for dim, value in zip(model.graph.output[0].type.tensor_type.shape.dim, output.shape):
    dim.dim_value = value
onnx.save_model(model, str(p))
onnx.checker.check_model(str(p))
print('Encoder output fixed:', output.name, output.shape)
