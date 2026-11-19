# Fluid Speech Pro → SM8750 QNN context binaries

This is a reconstruction recipe for the **completed 10-second host conversion**, not a claim that a fresh reconstruction is byte-identical or that the phone runs it correctly. The supporting scripts are preserved here; model checkpoints, FLEURS audio, SDK, generated graphs, and binaries are deliberately not committed. Keep them outside the repository in a durable, access-controlled work directory. Check all applicable model/dataset/SDK licenses before downloading or distributing.

## Evidence and limits

The original export and all three QAIRT conversions, host library builds, context generation and context metadata reads succeeded. One real-input host HTP-emulator comparison succeeded for the decoder and joiner. The encoder HTP emulator began execution but exceeded 300 seconds; no end-to-end transcript or WER, phone NPU behavior, latency, power, or CPU coexistence was validated. A host QNN CPU backend attempt failed graph composition and is not an HTP-device test. Installation/readiness is not inference validation. This recipe and adapted scripts have only been syntax/path checked since capture; dependency installation and a fresh full build remain to be verified.

Source: [FluidVoice-Labs/fluid-speech-pro](https://huggingface.co/FluidVoice-Labs/fluid-speech-pro) revision `46027a3bffc5817ce8f9b29add0a062610134a90`, `fluid-speech-pro.nemo`, 2,628,075,520 bytes, SHA-256 `f3e377b792ae5a52a3c071adb7eba5504306f2f4bdcf933ae9da2cbcab6f0449`. This is the NeMo checkpoint, **not** the app's separate pinned INT8 ONNX model. Fluid's model is CC BY 4.0; retain attribution and inspect its license/notice. The pinned Fluid `fluid_speech.py` loader attaches `encoder.fv_insert` after subsampling and strictly reloads its weights; `clean_mode=False` was used. Plain NeMo loading does not reproduce this model.

Export used CPython 3.10.20, NeMo 2.7.2, PyTorch/torchaudio 2.6.0+cpu, ONNX 1.17.0, ONNX Runtime 1.23.2, numpy 1.26.4 and opset 13. The actual `wrapper.py`, `generate_config.py` and `device_info.py` match sherpa-onnx revision `935e5ded` byte-for-byte, verified against upstream. This does not by itself prove the originally downloaded files came from that commit. The installed package snapshots [export](export-environment.txt) and [conversion](conversion-environment.txt) record observed versions, **not** wheel hashes, indexes or a solved lockfile; provisioning may require manual dependency resolution. The loader used Python's `tarfile.extract(..., filter='data')`, so use a patched 3.10 with that backport.

SDK: QAIRT/QNN `v2.40.0.251030114326_189385`, ZIP 1,403,532,983 bytes, SHA-256 `423ab04883150ab6c18de01f2adf23829fa9e8602a5a6b573473e3516921a3b9`. Obtain this exact SDK through an authorized distribution; no SDK or credentials are provided. SM8750 context config is SoC ID 69, HTP v79, 8 MB VTCM, graph optimization `O=3`, burst profile. Contexts are not generic across all Qualcomm chips/runtimes.

## Prerequisites and environment

Use Linux x86-64, Python 3.10.20 with shared `libpython3.10.so.1.0`, clang, make, libc++/libc++abi, unzip, curl, sha256sum, and enough RAM and disk. Avoid whitespace in work paths: QNN input-list rows split on whitespace. The original host had 2.6 GB checkpoint, ~1.6 GiB FLEURS shards, 2.1 GiB export venv, 692 MiB converter venv, 3.2 GiB extracted SDK, 1.3 GiB quant intermediates, 613 MiB contexts, plus ONNX external weights and temporary copies. Allow substantial additional temporary space and RAM; peak memory was not measured. On the original host encoder conversion took 273 s and context generation 134 s; export, calibration, library generation and downloads add considerable time. Do not schedule this in a space-pruned temporary directory.

From the repository root, set paths **to your own locations**, not inside the repository. `QNN_ZIP` is an already acquired SDK archive. `SRC` and `WORK` must be distinct and initially empty; never overwrite existing work. `TOOLS` points at this committed directory.

```bash
set -e -o pipefail
export TOOLS="$PWD/docs/development/fluid-qnn"
export BASE="${BASE:?set BASE to an absolute, durable work directory without spaces}"
export QNN_ZIP="${QNN_ZIP:?set QNN_ZIP to the authorized QAIRT 2.40 ZIP}"
export SRC="$BASE/export" WORK="$BASE/convert"
mkdir -p "$SRC/sources" "$SRC/fleurs" "$WORK/quant" "$WORK/binary" "$WORK/logs" "$BASE/tmp"
export TMPDIR="$BASE/tmp" OMP_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 MKL_NUM_THREADS=4
printf '%s  %s\n' 423ab04883150ab6c18de01f2adf23829fa9e8602a5a6b573473e3516921a3b9 "$QNN_ZIP" | sha256sum -c -
unzip -q "$QNN_ZIP" -d "$BASE/sdk"
# Adjust this path to the archive's actual layout; verify it contains bin/envsetup.sh.
export QNN_SDK_ROOT="$BASE/sdk/qairt/2.40.0.251030"
test -f "$QNN_SDK_ROOT/bin/envsetup.sh"
python3.10 -m venv "$BASE/export-venv"
python3.10 -m venv "$BASE/convert-venv"
```

Provision export dependencies in `export-venv` and converter dependencies in `convert-venv` according to the snapshots and QAIRT SDK documentation. For example, install the CPU torch 2.6.0 wheel family from the official PyTorch CPU wheel index, then the remaining recorded packages with pip; the snapshot may include platform-specific or unavailable packages, and is not guaranteed to install unchanged. No complete installation command/lock was captured. In particular use NeMo's ASR extras and `kaldi-native-fbank`, `pyarrow`, `soundfile`, `librosa`, `onnxruntime`, `onnx`, `numpy<2` for export/calibration. Converter Python needs the SDK converter dependencies, including ONNX 1.17.0, numpy 1.26.4 and protobuf 5.29.6. Check actual versions and save `python -m pip freeze` locally. The original converter warned that `onnxsim` was absent and proceeded without simplification; installing it may alter output.

One starting point for provisioning (not itself verified in a fresh environment) is:

```bash
"$BASE/export-venv/bin/python" -m pip install --extra-index-url https://download.pytorch.org/whl/cpu -r "$TOOLS/export-environment.txt"
"$BASE/convert-venv/bin/python" -m pip install -r "$TOOLS/conversion-environment.txt"
"$BASE/export-venv/bin/python" -c 'import torch, nemo.collections.asr, onnx, onnxruntime, numpy, pyarrow.parquet, soundfile, librosa, kaldi_native_fbank; print(torch.__version__)'
```

If installation fails, resolve the exact missing wheel or dependency against the captured versions and SDK requirements; the snapshots alone do not guarantee resolution. `pip check` and import checks are useful but do not validate model export or conversion.

If shared-library errors occur, expose your Python `lib/` and installed libc++ directory through `LD_LIBRARY_PATH` (and libc++ through `LIBRARY_PATH` as needed). The original host used local Debian libc++ packages, not sudo. Set `LDFLAGS='-Wl,-z,max-page-size=16384'` for library generation. Unset `CPLUS_INCLUDE_PATH` before generating libraries: mixed libc++/libstdc++ headers caused a real build failure. Source SDK `envsetup.sh` *after* activating the conversion venv; check `qnn-onnx-converter --help` and `qnn-context-binary-generator --version` against the stated SDK.

## Fetch exact inputs

```bash
export MODEL_REV=46027a3bffc5817ce8f9b29add0a062610134a90
export SHERPA_REV=935e5ded
export MODEL_URL="https://huggingface.co/FluidVoice-Labs/fluid-speech-pro/resolve/$MODEL_REV"
export SHERPA_URL="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$SHERPA_REV"
curl -fL "$MODEL_URL/fluid-speech-pro.nemo" -o "$SRC/fluid-speech-pro.nemo"
curl -fL "$MODEL_URL/fluid_speech.py" -o "$SRC/sources/fluid_speech.py"
curl -fL "$SHERPA_URL/scripts/nemo/qnn/parakeet-tdt/wrapper.py" -o "$SRC/sources/wrapper.py"
curl -fL "$SHERPA_URL/scripts/qnn/generate_config.py" -o "$SRC/sources/generate_config.py"
curl -fL "$SHERPA_URL/scripts/qnn/device_info.py" -o "$SRC/sources/device_info.py"
(cd "$SRC" && sha256sum -c <<'CHECKSUMS'
f3e377b792ae5a52a3c071adb7eba5504306f2f4bdcf933ae9da2cbcab6f0449  fluid-speech-pro.nemo
4a1873d8e84207c8ae19b57e0b4f85916309d33a18ad922ef204de6685b57e0e  sources/fluid_speech.py
d683d8a0273fcd214192be3eeb57f122c24887e54b208c241d43427ef247759a  sources/wrapper.py
2b2cdd47800647c9c23555a8adc0d34e2f997533435cd8131d7ee6875f3bb9fc  sources/generate_config.py
9c8cd36d0276d0e64f0b5f70b2170ee063c8fe4d4d274ddd9d4dc6068bb19a81  sources/device_info.py
CHECKSUMS
)
for lang in en_us it_it fr_fr; do
  curl -fL "https://huggingface.co/datasets/google/fleurs/resolve/refs%2Fconvert%2Fparquet/$lang/test/0000.parquet" -o "$SRC/fleurs/$lang.parquet"
done
(cd "$SRC/fleurs" && sha256sum -c <<'CHECKSUMS'
6428a4d04d3aac29e16b45e039bb1470a8bd7aa334cf92f7984c9c520d1f234d  en_us.parquet
6b648b2851cd0d0cf50254c9a7ecf8d91d7e41ecdb536f5614b10ef33b6b11a8  it_it.parquet
99a08ff00edbc7b6f040e23b33e49bc4a1fce38f364d50c603227d612fd797ef  fr_fr.parquet
CHECKSUMS
)
cp "$TOOLS/"{export_fluid_static,freeze_encoder_output,prepare_calibration}.py "$SRC/"
```

The FLEURS parquet conversion ref is mutable: if any hash fails, stop and locate the matching archived shards rather than silently substitute other samples. First six test rows of each shard were used, in order: en_us IDs `1904,1675,1950,1728,1972,1938`; it_it `1787,1883,1849,1814,1987,1974`; fr_fr `1987,1690,1857,1913,2004,1853`. Dataset rows are public but avoid putting audio, transcripts or calibration output into the project. Adapted `prepare_calibration.py` omits transcript text from the local manifest; this changes no model inputs.

## Export and calibration

```bash
source "$BASE/export-venv/bin/activate"
cd "$SRC"
python -B export_fluid_static.py --checkpoint "$SRC/fluid-speech-pro.nemo" --frames 1000 2>&1 | tee export.log
# Check the actual python exit status even when using tee.
test "${PIPESTATUS[0]}" -eq 0
EXPECTED_OUTPUT_FRAMES=125 python -B freeze_encoder_output.py
python -B - <<'PY'
import onnx
for name in ('encoder', 'decoder', 'joiner'):
    onnx.checker.check_model(name + '.onnx')
    m = onnx.load(name + '.onnx', load_external_data=False)
    if name == 'encoder':
        assert any('fv_insert' in n.name for n in m.graph.node), 'Fluid insert missing'
    print(name, 'checked')
PY
python -B prepare_calibration.py 2>&1 | tee calibration.log
test "${PIPESTATUS[0]}" -eq 0
wc -l encoder.txt decoder.txt joiner.txt
```

Keep every external tensor file beside `encoder.onnx` (the original had 297) and keep all three graphs with their referenced weights. The metadata fix takes the concrete output shape from ONNX Runtime and writes it to the encoder graph; it does not change model weights. A clean successful 10-second run produced 18 encoder, 778 decoder and 195 joiner list rows. The lists contain absolute local paths, so keep the workspace fixed or regenerate them after relocation. The decoder list is collected as evidence but was **not** used to quantize the float decoder.

The calibration script reads six test clips per language, selects channel 0, resamples to 16 kHz if needed, appends one second of silence, uses upstream-style 128-bin Kaldi fbank (dither 0, Hann, `is_librosa`), truncates or pads to 1000 frames, and normalizes per feature after padding. The encoder features and joiner/decoder inputs come from real speech and ONNX activations, not random calibration. Collection advances at least one frame per emission, so it is bounded and not faithful transcript decoding or accuracy evidence. Using test data for calibration also precludes an independent test-set WER claim on those clips.

## Convert and build contexts

```bash
deactivate
source "$BASE/convert-venv/bin/activate"
source "$QNN_SDK_ROOT/bin/envsetup.sh"
export LDFLAGS='-Wl,-z,max-page-size=16384'
cd "$WORK"
python -B "$TOOLS/prepare_native_calibration.py" --src "$SRC" --out "$WORK" --frames 1000
qnn-onnx-converter --input_network "$SRC/encoder.onnx" --output_path "$WORK/quant/encoder.cpp" --input_list "$WORK/encoder.txt" --use_native_input_files --act_bitwidth 16 --bias_bitwidth 32
qnn-onnx-converter --input_network "$SRC/decoder.onnx" --output_path "$WORK/quant/decoder.cpp" --float_bitwidth 16 --act_bitwidth 16 --bias_bitwidth 32
qnn-onnx-converter --input_network "$SRC/joiner.onnx" --output_path "$WORK/quant/joiner.cpp" --input_list "$SRC/joiner.txt" --use_native_input_files --act_bitwidth 16 --bias_bitwidth 32
unset CPLUS_INCLUDE_PATH
for m in encoder decoder joiner; do
  python -B "$SRC/sources/generate_config.py" --soc SM8750 --graph-name "$m" --output-dir "$WORK/config-$m" --qnn-sdk-root "$QNN_SDK_ROOT"
  qnn-model-lib-generator -c "quant/$m.cpp" -b "quant/$m.bin" -t x86_64-linux-clang -o quant/model_libs
  qnn-context-binary-generator --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" --model "$WORK/quant/model_libs/x86_64-linux-clang/lib$m.so" --output_dir "$WORK/binary" --binary_file "$m" --config_file "$WORK/config-$m/htp_backend_extensions.json"
  qnn-context-binary-utility --context_binary="$WORK/binary/$m.bin" --json_file="$WORK/binary/$m.json"
done
```

Run under a fail-fast shell (`set -e` and `set -o pipefail`) and save logs externally. The native-input flag refers to the converter's model input **layout**, not raw files prequantized to uint16: original encoder float32 `[1,128,1000]` is transposed to contiguous `[1,1000,128]`, checked by exact round trip for all 18 clips. Joiner uses all 195 activation pairs, in the order written. The decoder follows upstream floating conversion. `-t x86_64-linux-clang` is sufficient to produce contexts here and avoids Android NDK for model-library generation; these host `.so` files are not the Android runtime libraries.

### Expected 10-second tensor contracts

| Graph | Export ONNX input → output | Generated QNN inputs → outputs |
|---|---|---|
| encoder | `x` float32 `[1,128,1000]` → `encoder_out` float32 `[1,1024,125]` | `x` UFIXED16 `[1,1000,128]` → `encoder_out` UFIXED16 `[1,125,1024]` |
| decoder | `y` int32 `[1,1]`, `h,c` float32 `[2,1,640]` → `decoder_out` `[1,1,640]`, `next_h,next_c` `[2,1,640]` float32 | `y` INT32 `[1,1]`, `h,c` FLOAT16 `[2,640,1]` → `decoder_out` FLOAT16 `[1,1,640]`, `next_h,next_c` FLOAT16 `[2,1,640]` |
| joiner | `encoder_out` float32 `[1,1,1024]`, `decoder_out` float32 `[1,1,640]` → `log_probs` float32 `[1,1,1,8198]` | `encoder_out` UFIXED16 `[1,1024,1]`, `decoder_out` UFIXED16 `[1,640,1]` → `log_probs` UFIXED16 `[1,1,1,8198]` |

Read generated `binary/*.json` to verify these, not just filenames or successful exit codes. Original encoder C++ contained 278 `fv_insert` references. Vocabulary is 8192 pieces, blank ID 8192, plus five duration logits in the 8198 joiner outputs.

## Tokens, hashes and packaging

The exported table ends with `<blk> 8192`. The **historical packaged table** instead ends in a literal space followed by `8192`; this is the pinned on-disk artifact format, not a claim that the QNN recognizer consumes a space blank. Current app code validates this table and generates an app-private temporary copy ending in `<blk> 8192` for the sherpa QNN recognizer, then removes that copy. Do not edit the installed pinned file in place.

```bash
python - "$SRC/tokens.txt" "$WORK/binary/tokens.txt" <<'PY'
from pathlib import Path
import sys
lines = Path(sys.argv[1]).read_text(encoding='utf-8').splitlines()
assert len(lines) == 8193 and lines[-1] == '<blk> 8192'
assert [int(x.rsplit(' ', 1)[1]) for x in lines] == list(range(8193))
assert len({x.rsplit(' ', 1)[0] for x in lines}) == 8193
lines[-1] = ' 8192'
Path(sys.argv[2]).write_text('\n'.join(lines) + '\n', encoding='utf-8')
PY
(cd "$WORK/binary" && sha256sum encoder.bin decoder.bin joiner.bin tokens.txt)
```

Original generated outputs (byte identity is a useful diagnostic, **not guaranteed** on another host/dependency set):

| File | Bytes | SHA-256 |
|---|---:|---|
| encoder.bin | 612241408 | `c964659bc74dfeb00f67e7142af8e9abbda8bea68c26df3408f5dda45bed7323` |
| decoder.bin | 23715840 | `e292ef57f04425d6d083c0a87cd3f59ff547ebd207ec7653650ab3d76707aa1b` |
| joiner.bin | 6537216 | `3b27d31ccdeb8ecc02d5b0b048fb05c96ce4815c8eb4aa17a85b1bb10f87e404` |
| tokens.txt | 93934 | `06c76ebf093e64fbcc1699b42a174022f3e6c8c8073090e8de782403f5c8905b` |

Build a ZIP with exactly these four regular files at its root (no model directory prefix, no metadata JSON), then measure its actual size and SHA-256. For example:

```bash
python - "$WORK/binary" "$WORK/fluid-qnn.zip" <<'PY'
import sys, zipfile
from pathlib import Path
src, dst = Path(sys.argv[1]), Path(sys.argv[2])
with zipfile.ZipFile(dst, 'w', compression=zipfile.ZIP_STORED) as z:
    for name in ('encoder.bin', 'decoder.bin', 'joiner.bin', 'tokens.txt'):
        z.write(src / name, arcname=name)
with zipfile.ZipFile(dst) as z:
    assert z.namelist() == ['encoder.bin', 'decoder.bin', 'joiner.bin', 'tokens.txt']
    assert z.testzip() is None
print(dst.stat().st_size)
PY
sha256sum "$WORK/fluid-qnn.zip"
```

The historical 10-second catalog pinned a ZIP at 642588808 bytes and SHA-256 `f76d03ca188744dfb576890aee4a81b6987dc89a1e71b44ebabcad6817de4ced`. ZIP metadata/compression affect that hash; the example need not reproduce the original archive bytes. A new archive requires deliberate updates of the catalog archive URL/size/hash **and** every extracted file size/hash, plus review of installation constraints. Do not publish or change the app as part of this recipe without authorization.

## Optional host smoke checks

Use only public or explicitly authorized audio and keep results private. `qnn-net-run` reads float input by default; the decoder's `y` must be native int32. Create one-row input lists from the existing calibration lists and compare against ONNX:

```bash
for m in encoder decoder joiner; do
  if [ "$m" = encoder ]; then list="$WORK/encoder.txt"; else list="$SRC/$m.txt"; fi
  python -c 'from pathlib import Path; import sys; Path(sys.argv[2]).write_text(Path(sys.argv[1]).read_text().splitlines()[0]+"\n")' "$list" "$WORK/one-$m.txt"
done
for m in decoder joiner; do
  extra=(); [ "$m" != decoder ] || extra=(--native_input_tensor_names 'decoder:y')
  qnn-net-run --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" --retrieve_context "$WORK/binary/$m.bin" --config_file "$WORK/config-$m/htp_backend_extensions.json" --input_list "$WORK/one-$m.txt" --output_dir "$WORK/htp-$m" "${extra[@]}"
  python -B "$TOOLS/compare_onnx.py" "$WORK" "$SRC" "$m"
done
# Encoder emulation may run for a long time; original attempt timed out at 300 seconds.
timeout 300 qnn-net-run --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" --retrieve_context "$WORK/binary/encoder.bin" --config_file "$WORK/config-encoder/htp_backend_extensions.json" --input_list "$WORK/one-encoder.txt" --output_dir "$WORK/htp-encoder"
```

Original single-sample decoder RMSE was 0.001026 (output), 0.001398 (hidden), 0.002797 (cell); correlations 0.999670, 0.999961, 0.999978 respectively. Joiner logits RMSE was 1.30639, correlation 0.997162; token and duration argmax matched (8192 and 4). Different quantization can differ. These checks do **not** demonstrate recognition accuracy or phone correctness. Validate device execution, initialization/lifecycle, long-utterance chunking, CPU coexistence, and WER against independent human references separately.

## Android runtime and JNI provenance

The app's QNN-enabled sherpa AAR retains CPU libraries and uses JNI from the upstream demo, Kotlin API rebuilt from matching sherpa revision `935e5ded`, and QAIRT 2.40 V79 libraries. The CPU AAR and the demo both identify as 2.0.1 but have incompatible JNI/Kotlin field contracts: never mix the original CPU Kotlin API with the demo JNI. Existing `app/libs/sherpa-onnx-2.0.1-qnn-v79-kotlin-935e5ded.aar` had SHA-256 `5cb53d315c0fea83ad171c417f57deb8eec893f2b33cd6bef332662170b91f56` at capture. Its arm64 JNI SHA-256 was `4d2a3062c75fa0e2b1821d144163cec283f80313574e75bd5d4fb8e72504d722`. Archive inspection showed `libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so`, and `libQnnHtpV79Skel.so`, alongside sherpa/ONNX Runtime libraries. Gradle preserves the DSP skeleton and uses extracted legacy JNI packaging for FastRPC.

If the AAR is unavailable, the exact original demo APK identity and full repack/build commands were not captured in conversion evidence: obtaining matching demo JNI, rebuilding the matching Kotlin API, retaining CPU bindings, integrating SDK V79 libraries and verifying the resulting JNI contract is an **unresolved separate reconstruction task**. Follow [upstream Android QNN build guidance](https://k2-fsa.github.io/sherpa/onnx/qnn/build-android-demo.html), inspect its actual output and licenses, and do not assume any 2.0.1 APK matches. Check the current app source and Gradle configuration before integration.

## 30-second SM8750 conversion

The 30-second host conversion has now completed with the same pinned checkpoint, wrapper, SDK and three language shards. To reproduce it, follow the recipe above with these changes:

1. Export with `--frames 3000`, then run `EXPECTED_OUTPUT_FRAMES=375 python -B freeze_encoder_output.py`.
2. Run `prepare_calibration.py` against the new graphs; it reads the encoder input shape and regenerates all audio features and ONNX activations. Do not reuse the 1000-frame raw features. Run `prepare_native_calibration.py` with `--frames 3000` and use its generated encoder list for conversion.
3. Use the same converter options and SM8750 config and regenerate all three context binaries. Inspect all tensor metadata with the SDK utility. The new encoder contract is `x` UFIXED16 `[1,3000,128]` → `encoder_out` UFIXED16 `[1,375,1024]`; decoder and joiner signatures are unchanged.
4. Generate the same historical space-blank token file, package the four root files, and check all sizes and hashes before updating a catalog. The compressed archive produced in this run was 463425217 bytes, SHA-256 `f5402009bff3b9dbb32ecaf302a79c59644e233a63daa5072581902b8f997a02`. Its encoder was 633352192 bytes, SHA-256 `d980fbbcbd71fbb2fbb90b91f3914cd3be72f28588effdf6f97c568b35f66443`; decoder 23715840 bytes, `e292ef57f04425d6d083c0a87cd3f59ff547ebd207ec7653650ab3d76707aa1b`; joiner 6537216 bytes, `03a1544841488768129220fdaf6912d7370e242c756f0ec4ec67240d66b6f556`; tokens 93934 bytes, `06c76ebf093e64fbcc1699b42a174022f3e6c8c8073090e8de782403f5c8905b`.

This run used 18 real speech clips, 602 decoder state samples and 399 joiner activation pairs. ONNX simplification was unavailable, so the converter used unsimplified models. The SDK parsed the generated context contracts and the archived files were checked against the generated bytes. At conversion time no 30-second S25 inference, transcription, accuracy, memory or latency test had been performed. The app's current QNN runtime pads shorter audio to the fixed 30-second window, so preview cost can increase. Conversion, memory and runtime cost must not be extrapolated from the 10-second model.

## 15-second SM8750 conversion

Follow the same recipe with `--frames 1500`, `EXPECTED_OUTPUT_FRAMES=188` and `prepare_native_calibration.py --frames 1500`. Regenerate calibration from the new graphs. The SDK context utility reported encoder input `x` UFIXED16 `[1,1500,128]` and output `encoder_out` UFIXED16 `[1,188,1024]`; decoder and joiner tensor signatures matched the earlier conversions. The run used 18 FLEURS clips across three languages, including only two at least 15 seconds long, with 810 decoder state samples and 257 joiner pairs. ONNX simplification was unavailable. The flat four-file ZIP was 458310578 bytes, SHA-256 `87183120a63049f49b0db1dfd43f5870f86e95e2467e44556792cb8c81058cec`; per-file hashes are pinned in the app catalog. Context metadata, archive CRC and file hashes were verified, but no S25 inference, accuracy, latency or memory test was performed for this version.
