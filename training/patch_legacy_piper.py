"""Make the pinned legacy VAD honor a single CPU thread before session creation."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / '.tools/piper/src/python/piper_train/norm_audio/vad.py'
source = path.read_text(encoding='utf-8')
old = '''        self.session = onnxruntime.InferenceSession(onnx_path)
        self.session.intra_op_num_threads = 1
        self.session.inter_op_num_threads = 1'''
new = '''        options = onnxruntime.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        self.session = onnxruntime.InferenceSession(
            onnx_path, sess_options=options, providers=["CPUExecutionProvider"]
        )'''
if old in source:
    path.write_text(source.replace(old, new), encoding='utf-8')
elif new not in source:
    raise RuntimeError('Unexpected Piper VAD source; refusing an unverified patch')
