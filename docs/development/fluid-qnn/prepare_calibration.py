#!/usr/bin/env python3
"""Generate QNN calibration with FLEURS speech and real ONNX activations."""
import io
import json
from pathlib import Path
import numpy as np
import onnxruntime as ort
import pyarrow.parquet as pq
import soundfile as sf
import librosa
import kaldi_native_fbank as knf

ROOT = Path(__file__).resolve().parent
raw = ROOT / 'calib_raw'
raw.mkdir(exist_ok=True)
so = ort.SessionOptions()
so.inter_op_num_threads = 1
so.intra_op_num_threads = 4
print('Loading ONNX sessions', flush=True)
sessions = {n: ort.InferenceSession(str(ROOT / f'{n}.onnx'), sess_options=so, providers=['CPUExecutionProvider']) for n in ('encoder', 'decoder', 'joiner')}
enc, dec, join = (sessions[n] for n in ('encoder', 'decoder', 'joiner'))
for n, s in sessions.items():
    print(n, 'inputs', [(i.name, i.shape, i.type) for i in s.get_inputs()], 'outputs', [(i.name, i.shape, i.type) for i in s.get_outputs()], flush=True)
shape = enc.get_inputs()[0].shape
assert len(shape) == 3 and shape[0] == 1 and isinstance(shape[1], int) and isinstance(shape[2], int)
_, feat_dim, max_len = shape
vocab_size = sum(1 for _ in open(ROOT / 'tokens.txt', encoding='utf8')) - 1
print('blank', vocab_size, flush=True)

def features(audio):
    opts = knf.FbankOptions()
    opts.frame_opts.dither = 0
    opts.frame_opts.remove_dc_offset = False
    opts.frame_opts.window_type = 'hann'
    opts.mel_opts.low_freq = 0
    opts.mel_opts.num_bins = feat_dim
    opts.mel_opts.is_librosa = True
    fb = knf.OnlineFbank(opts)
    fb.accept_waveform(16000, audio)
    x = np.stack([np.asarray(fb.get_frame(i)) for i in range(fb.num_frames_ready)])
    x = x[:max_len]
    if len(x) < max_len:
        x = np.pad(x, ((0, max_len-len(x)), (0, 0)))
    x = (x - x.mean(axis=0, keepdims=True)) / (x.std(axis=0, keepdims=True) + 1e-5)
    return np.ascontiguousarray(x.T[None], dtype=np.float32)

def save(name, value):
    path = raw / name
    np.ascontiguousarray(value).tofile(path)
    return str(path)

enc_lines, dec_lines, join_lines, manifest = [], [], [], []
for lang in ('en_us', 'it_it', 'fr_fr'):
    parquet = pq.ParquetFile(ROOT / 'fleurs' / f'{lang}.parquet')
    batch = next(parquet.iter_batches(batch_size=6, columns=['audio','id','raw_transcription']))
    for i, row in enumerate(batch.to_pylist()):
        audio, sr = sf.read(io.BytesIO(row['audio']['bytes']), dtype='float32', always_2d=True)
        audio = audio[:, 0]
        if sr != 16000:
            audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
        audio = np.concatenate([audio, np.zeros(16000, dtype=np.float32)])
        x = features(audio)
        prefix = f'{lang}_{i}'
        enc_lines.append(save(f'{prefix}_enc.raw', x))
        eout = enc.run(None, {enc.get_inputs()[0].name: x})[0].transpose(0, 2, 1)
        ins = dec.get_inputs()
        h = np.zeros(ins[1].shape, dtype=np.float32)
        c = np.zeros(ins[2].shape, dtype=np.float32)
        token = vocab_size
        step = 0
        def decoder_run(token, h, c, step):
            y = np.array([[token]], dtype=np.int32)
            paths = [save(f'{prefix}_dec_{step}_{n}.raw', a) for n,a in [('y',y),('h',h),('c',c)]]
            dec_lines.append(' '.join(paths))
            return dec.run(None, dict(zip([x.name for x in ins], [y,h,c])))
        d, h, c = decoder_run(token, h, c, step)
        t = 0
        while t < eout.shape[1]:
            ev = np.ascontiguousarray(eout[:, t:t+1, :])
            if t % 5 == 0:
                paths = [save(f'{prefix}_join_{t}_{n}.raw', a) for n,a in [('e',ev),('d',d)]]
                join_lines.append(' '.join(paths))
            scores = join.run(None, {join.get_inputs()[0].name: ev, join.get_inputs()[1].name: d})[0].reshape(-1)
            token = int(np.argmax(scores[:vocab_size+1]))
            duration = int(np.argmax(scores[vocab_size+1:]))
            if token != vocab_size:
                step += 1
                d, h, c = decoder_run(token, h, c, step)
            # Bound repeated emissions, keeping real joiner activations for calibration.
            t += max(duration, 1)
        manifest.append({'lang':lang, 'id':row['id'], 'sample_rate':sr, 'features':enc_lines[-1], 'decoded_steps':step})
        print(prefix, 'decoded_steps', step, flush=True)
for name, lines in [('encoder.txt',enc_lines), ('decoder.txt',dec_lines), ('joiner.txt',join_lines)]:
    (ROOT / name).write_text('\n'.join(lines)+'\n')
(ROOT / 'calibration_manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
print('calibration rows:', len(enc_lines), len(dec_lines), len(join_lines), flush=True)
