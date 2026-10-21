"""Apply OS-enforced limits before importing numerical libraries (Windows only)."""
import ctypes as c
from ctypes import wintypes as w
import hashlib
import os
from pathlib import Path

_handles = []


def apply_budget():
    if os.name != "nt":
        raise RuntimeError("This safe launcher requires Windows Job Objects")
    kernel = c.WinDLL("kernel32", use_last_error=True)
    signatures = {
        "CreateJobObjectW": ([c.c_void_p, w.LPCWSTR], w.HANDLE),
        "CreateMutexW": ([c.c_void_p, w.BOOL, w.LPCWSTR], w.HANDLE),
        "GetCurrentProcess": ([], w.HANDLE),
        "SetInformationJobObject": ([w.HANDLE, c.c_int, c.c_void_p, w.DWORD], w.BOOL),
        "QueryInformationJobObject": ([w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.c_void_p], w.BOOL),
        "AssignProcessToJobObject": ([w.HANDLE, w.HANDLE], w.BOOL),
        "SetPriorityClass": ([w.HANDLE, w.DWORD], w.BOOL),
    }
    for name, (args, result) in signatures.items():
        fn = getattr(kernel, name)
        fn.argtypes, fn.restype = args, result

    def check(value):
        if not value:
            raise c.WinError(c.get_last_error())
        return value

    identity = hashlib.sha256(str(Path(__file__).resolve().parent).lower().encode()).hexdigest()[:20]
    mutex = check(kernel.CreateMutexW(None, False, "Local\\akiat_asr_" + identity))
    if c.get_last_error() == 183:
        raise RuntimeError("Another ASR process is already running for this repository")
    _handles.append(mutex)

    class Basic(c.Structure):
        _fields_ = [("ProcessTime", c.c_int64), ("JobTime", c.c_int64),
                    ("Flags", w.DWORD), ("MinWorkingSet", c.c_size_t),
                    ("MaxWorkingSet", c.c_size_t), ("ActiveProcesses", w.DWORD),
                    ("Affinity", c.c_size_t), ("Priority", w.DWORD),
                    ("Scheduling", w.DWORD)]

    class Extended(c.Structure):
        _fields_ = [("Basic", Basic), ("IO", c.c_uint64 * 6),
                    ("ProcessMemory", c.c_size_t), ("JobMemory", c.c_size_t),
                    ("PeakProcess", c.c_size_t), ("PeakJob", c.c_size_t)]

    class Rate(c.Structure):
        _fields_ = [("Flags", w.DWORD), ("Rate", w.DWORD)]

    job = check(kernel.CreateJobObjectW(None, None))
    _handles.append(job)
    limits = Extended()
    limits.Basic.Flags = 0x200  # JOB_OBJECT_LIMIT_JOB_MEMORY
    limits.JobMemory = 6 * 1024**3
    rate = Rate(0x1 | 0x4, 2000)  # Enabled, hard cap: 20% of total CPU
    check(kernel.SetInformationJobObject(job, 9, c.byref(limits), c.sizeof(limits)))
    check(kernel.SetInformationJobObject(job, 15, c.byref(rate), c.sizeof(rate)))
    process = kernel.GetCurrentProcess()
    check(kernel.SetPriorityClass(process, 0x4000))  # BELOW_NORMAL_PRIORITY_CLASS
    check(kernel.AssignProcessToJobObject(job, process))
    actual_limits, actual_rate = Extended(), Rate()
    check(kernel.QueryInformationJobObject(job, 9, c.byref(actual_limits), c.sizeof(actual_limits), None))
    check(kernel.QueryInformationJobObject(job, 15, c.byref(actual_rate), c.sizeof(actual_rate), None))
    if actual_limits.JobMemory != limits.JobMemory or actual_rate.Rate != 2000:
        raise RuntimeError("Windows resource limits were not applied")
    print(f"Safety limits active: PID={os.getpid()}; CPU<=20%; committed memory<=6 GiB; priority=BelowNormal",
          flush=True)
