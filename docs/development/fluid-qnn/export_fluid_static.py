#!/usr/bin/env python3
"""Export Fluid Speech Pro using sherpa-onnx's QNN TDT wrappers."""
import argparse
import os
import sys
import torch
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'sources'))
import fluid_speech
import wrapper

@torch.no_grad()
def main():
    p = argparse.ArgumentParser()
    p.add_argument('--checkpoint', default='fluid-speech-pro.nemo')
    p.add_argument('--frames', type=int, default=1000)
    args = p.parse_args()
    print('loading model', args.checkpoint, flush=True)
    m = fluid_speech.load(args.checkpoint, map_location='cpu', clean_mode=False)
    assert hasattr(m.encoder, 'fv_insert') and m.encoder.pre_encode._forward_hooks
    print('Fluid residual hook attached', m.encoder.fv_insert, flush=True)
    m.decoder._prepare_for_export()
    m.joint._prepare_for_export()
    vocab = m.joint.vocabulary
    assert m.decoding.decoding._blank_index == len(vocab)
    with open('tokens.txt', 'w', encoding='utf-8') as f:
        for i, piece in enumerate(vocab):
            f.write(f'{piece} {i}\n')
        f.write(f'<blk> {len(vocab)}\n')
    print('vocabulary', len(vocab), 'blank', len(vocab), 'features', m.cfg['preprocessor']['features'], flush=True)
    print('export decoder', flush=True)
    wrapper.export_decoder(m)
    print('export joiner', flush=True)
    wrapper.export_joiner(m, m.joint.enc.in_features, m.joint.pred.in_features)
    print('export encoder', flush=True)
    wrapper.export_encoder(m, max_len=args.frames, feat_dim=m.cfg['preprocessor']['features'])
    print('export complete', flush=True)

if __name__ == '__main__':
    main()
