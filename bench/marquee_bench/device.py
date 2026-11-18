"""Guarded ADB access for benchmark captures.

Safety rules enforced here (and unit-tested offline):

* Every command targets an explicit serial (``adb -s SERIAL``). There is no
  default device: :class:`Device` refuses an empty serial.
* Key/HID input is only sent through :meth:`Device.send_keys` /
  :meth:`Device.run_hid`, which first verify that the target package owns the
  resumed (foreground) activity. If anything else is in front, they raise
  :class:`ForegroundError` and send nothing.
* Read-only helpers (identity, compile state, APK hash, meminfo, gfxinfo) never
  modify the device. ``gfxinfo reset`` only clears the app's own frame-stats
  ring buffer.

Nothing here stores credentials. Captures need no server credentials at all.
"""
from __future__ import annotations

import os
import re
import shlex
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from typing import Callable, Sequence

Runner = Callable[[Sequence[str], float], str]


class ForegroundError(RuntimeError):
    """The target package is not in the foreground; no input was sent."""


class DeviceError(RuntimeError):
    pass


def _default_runner(argv: Sequence[str], timeout: float) -> str:
    return subprocess.run(list(argv), check=True, capture_output=True, text=True,
                          timeout=timeout).stdout


# Resumed-activity records in ``dumpsys activity activities``, Android 10-14:
#   "  mResumedActivity: ActivityRecord{1a2b u0 pkg/.Main t12}"   (stack/task sections)
#   "  ResumedActivity: ActivityRecord{1a2b u0 pkg/.Main t12}"    (display/task sections)
#   "  topResumedActivity=ActivityRecord{1a2b u0 pkg/.Main t12}"  (Android 12+, focused display)
# Anchored at the start of the line so history records such as mLastResumedActivity,
# mLastPausedActivity or mPausingActivity never match.
_RESUMED = re.compile(
    r"^[ \t]*(mResumedActivity|ResumedActivity|topResumedActivity)[ \t]*[:=][ \t]*"
    r"ActivityRecord\{[^}\n]*?\s([\w.]+)/([\w.$]+)", re.M)


def resumed_packages(activity_dump: str) -> set[str]:
    """Package names that own a resumed activity in ``dumpsys activity activities``."""
    return {m.group(2) for m in _RESUMED.finditer(activity_dump)}


def is_foreground(activity_dump: str, package: str) -> bool:
    """True only if ``package`` owns the resumed activity. Fails closed.

    If the dump has a ``topResumedActivity`` record (Android 12+), that record alone decides.
    Otherwise ``package`` must own every resumed-activity record found. No record at all, or a
    record owned by another package, means "not in the foreground".
    """
    records = [(m.group(1), m.group(2)) for m in _RESUMED.finditer(activity_dump)]
    top = {pkg for kind, pkg in records if kind == "topResumedActivity"}
    owners = top or {pkg for _, pkg in records}
    return owners == {package}


def parse_getprop(raw: str) -> dict[str, str]:
    return dict(re.findall(r"^\[([^\]]+)\]: \[([^\]]*)\]", raw, re.M))


def parse_compile_status(package_dump: str) -> list[str]:
    """``[status=speed] [reason=...]`` fragments from ``dumpsys package <pkg>``."""
    return re.findall(r"\[status=([\w-]+)\]\s*\[reason=([\w-]+)\]", package_dump)


def parse_meminfo_summary(raw: str) -> dict[str, int]:
    """App Summary PSS values (KiB) from ``dumpsys meminfo <pkg>``."""
    out = {}
    for key, label in (("java_heap_kib", "Java Heap"), ("native_heap_kib", "Native Heap"),
                       ("code_kib", "Code"), ("stack_kib", "Stack"),
                       ("graphics_kib", "Graphics"), ("private_other_kib", "Private Other"),
                       ("system_kib", "System")):
        m = re.search(rf"^\s*{label}:\s+(\d+)", raw, re.M)
        if m:
            out[key] = int(m.group(1))
    m = re.search(r"TOTAL PSS:\s+(\d+)", raw)
    if m:
        out["total_pss_kib"] = int(m.group(1))
    return out


@dataclass
class Device:
    serial: str
    package: str
    adb: str = "adb"
    adb_server_port: int | None = None
    runner: Runner = field(default=_default_runner, repr=False)

    def __post_init__(self):
        if not self.serial or not self.serial.strip():
            raise DeviceError("an explicit --serial is required; refusing to guess a device")
        if not re.fullmatch(r"[A-Za-z][\w]*(\.[A-Za-z][\w]*)+", self.package or ""):
            raise DeviceError(f"invalid package name {self.package!r}")

    # -- plumbing ---------------------------------------------------------
    def _base(self) -> list[str]:
        base = [self.adb]
        if self.adb_server_port:
            base += ["-P", str(self.adb_server_port)]
        return base + ["-s", self.serial]

    def adb_cmd(self, *args: str, timeout: float = 15) -> str:
        return self.runner(self._base() + [str(a) for a in args], timeout)

    def shell(self, command: str, timeout: float = 15) -> str:
        return self.adb_cmd("shell", command, timeout=timeout)

    # -- read-only --------------------------------------------------------
    def identity(self) -> dict:
        props = parse_getprop(self.shell("getprop"))
        return {k: props.get(k) for k in (
            "ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
            "ro.build.version.sdk", "ro.product.cpu.abi", "ro.board.platform",
            "ro.build.fingerprint")}

    def compile_status(self) -> list[str]:
        return [f"{s}/{r}" for s, r in parse_compile_status(
            self.shell(f"dumpsys package {shlex.quote(self.package)}"))]

    def is_debuggable(self) -> bool:
        dump = self.shell(f"dumpsys package {shlex.quote(self.package)}")
        return bool(re.search(r"(pkgFlags|flags)=\[[^\]]*DEBUGGABLE", dump))

    def apk_sha256(self) -> str:
        paths = [line.removeprefix("package:").strip() for line in
                 self.shell(f"pm path {shlex.quote(self.package)}").splitlines() if line.strip()]
        if not paths:
            raise DeviceError(f"{self.package} is not installed")
        out = self.shell(f"sha256sum {shlex.quote(paths[0])}")
        sha = out.split()[0] if out.split() else ""
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise DeviceError("could not hash installed APK")
        return sha

    def pid(self) -> str | None:
        out = self.shell(f"pidof {shlex.quote(self.package)}").strip()
        return out.split()[0] if out else None

    def uptime_ns(self) -> int:
        return int(float(self.shell("cat /proc/uptime").split()[0]) * 1e9)

    def framestats(self) -> str:
        return self.shell(f"dumpsys gfxinfo {shlex.quote(self.package)} framestats", timeout=20)

    def reset_framestats(self) -> None:
        self.shell(f"dumpsys gfxinfo {shlex.quote(self.package)} reset")

    def meminfo(self) -> str:
        return self.shell(f"dumpsys meminfo {shlex.quote(self.package)}", timeout=30)

    def in_foreground(self) -> bool:
        return is_foreground(self.shell("dumpsys activity activities", timeout=20), self.package)

    def require_foreground(self) -> None:
        if not self.in_foreground():
            raise ForegroundError(
                f"{self.package} is not the resumed activity on {self.serial}; refusing to send input")

    # -- input (guarded) --------------------------------------------------
    def send_keys(self, *keycodes: int) -> None:
        if not keycodes:
            return
        for k in keycodes:
            if not isinstance(k, int) or not 0 < k < 400:
                raise ValueError(f"invalid keycode {k!r}")
        self.require_foreground()
        self.shell("input keyevent " + " ".join(str(k) for k in keycodes))

    def push_text(self, local_text: str, remote_path: str) -> None:
        if not re.fullmatch(r"/data/local/tmp/[\w.-]+", remote_path):
            raise ValueError("remote path must be a plain file under /data/local/tmp")
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            fh.write(local_text)
            tmp = fh.name
        try:
            self.adb_cmd("push", tmp, remote_path, timeout=20)
        finally:
            os.unlink(tmp)

    def run_hid(self, remote_path: str, timeout: float = 30) -> subprocess.Popen:
        """Start ``hid <file>`` in the background after a foreground check."""
        if not re.fullmatch(r"/data/local/tmp/[\w.-]+", remote_path):
            raise ValueError("remote path must be a plain file under /data/local/tmp")
        self.require_foreground()
        return subprocess.Popen(self._base() + ["shell", "hid", remote_path],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def remove_tmp(self, remote_path: str) -> None:
        if re.fullmatch(r"/data/local/tmp/[\w.-]+", remote_path):
            self.shell(f"rm -f {shlex.quote(remote_path)}")


def record_context(dev: Device) -> dict:
    """Everything that must accompany a result (see bench/README.md)."""
    return {
        "captured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "serial_redacted": True,
        "identity": dev.identity(),
        "package": dev.package,
        "apk_sha256": dev.apk_sha256(),
        "compile_status": dev.compile_status(),
        "debuggable": dev.is_debuggable(),
    }
