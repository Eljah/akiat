"""Run an installed legacy Piper trainer on CPU with a prepared corpus."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, required=True,
                        help="Piper preprocessed dataset with config.json and dataset.jsonl")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("outputs/piper_cpu"))
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    for path in (args.dataset_dir / "config.json", args.dataset_dir / "dataset.jsonl", args.checkpoint):
        if not path.is_file():
            parser.error(f"Missing required file: {path}")
    if args.checkpoint.suffix != ".ckpt":
        parser.error("A training .ckpt is required, not an inference ONNX model")
    if importlib.util.find_spec("piper_train") is None:
        parser.error("Legacy piper_train is not installed in this Python environment")
    env = os.environ.copy()
    env.update(CUDA_VISIBLE_DEVICES="", OMP_NUM_THREADS=str(args.threads),
               MKL_NUM_THREADS=str(args.threads))
    command = [
        sys.executable, "-m", "piper_train", "--dataset-dir", str(args.dataset_dir.resolve()),
        "--accelerator", "cpu", "--devices", "1", "--batch-size", "1",
        "--num-workers", "0", "--validation-split", "0.05", "--num-test-examples", "0",
        "--max_epochs", str(args.epochs), "--checkpoint-epochs", "2", "--precision", "32",
        "--resume_from_checkpoint", str(args.checkpoint.resolve()),
        "--default_root_dir", str(args.output.resolve()),
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "command.json").write_text(json.dumps(command, indent=2), encoding="utf-8")
    subprocess.run(command, env=env, check=True)


if __name__ == "__main__":
    main()
