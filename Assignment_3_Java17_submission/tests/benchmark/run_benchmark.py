"""Compile the Java benchmark, run it once, and preserve actual measurements."""

import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def hardware():
    cpu = platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "unknown")
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                               r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                cpu = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
        except OSError:
            pass
    return {
        "processor": cpu,
        "logical_processors": os.cpu_count(),
        "machine": platform.machine(),
        "platform": platform.platform(),
        "python_driver": platform.python_version(),
    }


def main():
    build = ROOT / ".build" / "benchmark"
    build.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src" / "Java").glob("*.java")) + [HERE / "Benchmark.java"]
    compile_command = ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(build),
                       *map(str, sources)]
    subprocess.run(compile_command, cwd=ROOT, check=True, timeout=60)
    command = ["java", "-Xms384m", "-Xmx384m", "-cp", str(build), "Benchmark"]
    completed = subprocess.run(command, cwd=ROOT, check=True, capture_output=True,
                               text=True, encoding="utf-8", timeout=180)
    report = json.loads(completed.stdout)
    report["run_completed_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    report["driver_command"] = "python tests/benchmark/run_benchmark.py"
    report["compile_command"] = "javac --release 17 -encoding UTF-8 -d .build/benchmark src/Java/*.java tests/benchmark/Benchmark.java"
    report["runtime_command"] = "java -Xms384m -Xmx384m -cp .build/benchmark Benchmark"
    report["hardware"] = hardware()
    report["source_sha256"] = {
        str(path.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sources
    }
    if completed.stderr.strip():
        report["runtime_stderr"] = completed.stderr
    output = HERE / "results.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("All benchmark forests validated; results saved to tests/benchmark/results.json")
    print("n,m,kruskal_median_ms,prim_median_ms")
    for measurement in report["measurements"]:
        median = measurement["median_ns"]
        print(f'{measurement["n"]},{measurement["m"]},'
              f'{median["kruskal"] / 1e6:.6f},{median["prim"] / 1e6:.6f}')


if __name__ == "__main__":
    main()
