import sys
from pathlib import Path
import numpy as np
import onnxruntime as ort

work=Path(sys.argv[1]).resolve()
src=Path(sys.argv[2]).resolve()
component=sys.argv[3]
frames=int(sys.argv[4]) if len(sys.argv)>4 else 1000
output_frames=int(sys.argv[5]) if len(sys.argv)>5 else 125
so=ort.SessionOptions(); so.intra_op_num_threads=4; so.inter_op_num_threads=1
sess=ort.InferenceSession(str(src/(component+'.onnx')),sess_options=so,providers=['CPUExecutionProvider'])
if component=='encoder':
 p=Path((work/'one-encoder.txt').read_text().strip())
 data={'x':np.fromfile(p,np.float32).reshape(1,frames,128).transpose(0,2,1).copy()}
elif component=='decoder':
 paths=(work/'one-decoder.txt').read_text().split(); data={'y':np.fromfile(paths[0],np.int32).reshape(1,1),'h':np.fromfile(paths[1],np.float32).reshape(2,1,640),'c':np.fromfile(paths[2],np.float32).reshape(2,1,640)}
else:
 paths=(work/'one-joiner.txt').read_text().split(); data={'encoder_out':np.fromfile(paths[0],np.float32).reshape(1,1,1024),'decoder_out':np.fromfile(paths[1],np.float32).reshape(1,1,640)}
ref=sess.run(None,data)
for meta, expect in zip(sess.get_outputs(),ref):
 actual=np.fromfile(work/('htp-'+component)/'Result_0'/(meta.name+'.raw'),np.float32)
 if component=='encoder': actual=actual.reshape(1,output_frames,1024).transpose(0,2,1).copy().flatten()
 else: actual=actual.flatten()
 expect=expect.flatten(); assert actual.shape==expect.shape, (actual.shape,expect.shape)
 delta=actual-expect; print(component,meta.name,'n=',len(actual),'finite=',bool(np.isfinite(actual).all()),'rmse=',float(np.sqrt(np.mean(delta**2))),'max_abs=',float(np.max(np.abs(delta))),'correlation=',float(np.corrcoef(actual,expect)[0,1]))
 if component=='joiner':
  print('  token argmax HTP/ONNX',int(actual[:8193].argmax()),int(expect[:8193].argmax()),'duration HTP/ONNX',int(actual[8193:].argmax()),int(expect[8193:].argmax()))
