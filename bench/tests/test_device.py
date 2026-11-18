import pytest

from marquee_bench.device import (Device, DeviceError, ForegroundError, is_foreground, parse_compile_status,
                                  parse_getprop, parse_meminfo_summary, resumed_packages)

ACT_FG = "  mResumedActivity: ActivityRecord{abc u0 com.example.app/.MainActivity t12}\n"
ACT_BG = "  mResumedActivity: ActivityRecord{abc u0 com.other.launcher/.Home t3}\n"


class Fake:
    def __init__(self, activity):
        self.activity, self.calls = activity, []

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        cmd = argv[-1]
        if cmd.startswith("dumpsys activity activities"):
            return self.activity
        return ""


def test_serial_is_required():
    for bad in ("", "   ", None):
        with pytest.raises(DeviceError):
            Device(bad, "com.example.app", runner=Fake(ACT_FG))


def test_package_name_validated():
    with pytest.raises(DeviceError):
        Device("tv:5555", "not a package; rm -rf /", runner=Fake(ACT_FG))


def test_every_command_targets_the_serial_and_port():
    fake = Fake(ACT_FG)
    d = Device("tv-serial", "com.example.app", adb_server_port=5999, runner=fake)
    d.send_keys(22)
    assert all(c[:5] == ["adb", "-P", "5999", "-s", "tv-serial"] for c in fake.calls)


def test_keys_refused_when_app_not_in_foreground():
    fake = Fake(ACT_BG)
    d = Device("tv", "com.example.app", runner=fake)
    with pytest.raises(ForegroundError):
        d.send_keys(22)
    assert not any("input keyevent" in c[-1] for c in fake.calls)


def test_hid_refused_when_app_not_in_foreground():
    d = Device("tv", "com.example.app", runner=Fake(ACT_BG))
    with pytest.raises(ForegroundError):
        d.run_hid("/data/local/tmp/x.json")


def test_hid_path_restricted():
    d = Device("tv", "com.example.app", runner=Fake(ACT_FG))
    with pytest.raises(ValueError):
        d.run_hid("/sdcard/../etc/x")


def test_keycodes_validated():
    d = Device("tv", "com.example.app", runner=Fake(ACT_FG))
    with pytest.raises(ValueError):
        d.send_keys("3; reboot")


def test_parsers():
    assert resumed_packages(ACT_FG + ACT_BG) == {"com.example.app", "com.other.launcher"}
    assert parse_getprop("[ro.product.model]: [TV]\n[x]: []\n") == {"ro.product.model": "TV", "x": ""}
    assert parse_compile_status("arm: [status=speed] [reason=cmdline]") == [("speed", "cmdline")]
    mem = """ App Summary
           Java Heap:    26064                          36556
         Native Heap:    43444                          44024
                Code:    18444                          99632
            Graphics:        0                              0
           TOTAL PSS:   100539            TOTAL RSS:   184760"""
    assert parse_meminfo_summary(mem) == {"java_heap_kib": 26064, "native_heap_kib": 43444, "code_kib": 18444,
                                          "graphics_kib": 0, "total_pss_kib": 100539}


# Synthetic ``dumpsys activity activities`` excerpts in the shapes printed by Android 10-14.
DUMP_Q = """ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  Stack #12: type=standard mode=fullscreen
    mResumedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
    mLastPausedActivity: ActivityRecord{bb2 u0 com.other.launcher/.Home t3}
  ResumedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
"""
DUMP_R_BACKGROUND = """Display #0 (activities from top to bottom):
  Stack #3: type=home mode=fullscreen
    mResumedActivity: ActivityRecord{cc3 u0 com.other.launcher/.Home t3}
    mLastPausedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
  mLastResumedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
 ResumedActivity:ActivityRecord{cc3 u0 com.other.launcher/.Home t3}
"""
DUMP_U = """Display #0 (activities from top to bottom):
  * Task{dd4 #12 type=standard A=10123:com.example.app}
    topResumedActivity=ActivityRecord{aa1 u0 com.example.app/.player.PlayerActivity t12}
    ResumedActivity: ActivityRecord{aa1 u0 com.example.app/.player.PlayerActivity t12}
  mLastPausedActivity: ActivityRecord{bb2 u0 com.other.launcher/.Home t3}
"""
DUMP_U_BACKGROUND = """Display #0 (activities from top to bottom):
    topResumedActivity=ActivityRecord{cc3 u0 com.other.launcher/.Home t3}
    ResumedActivity: ActivityRecord{cc3 u0 com.other.launcher/.Home t3}
  mLastResumedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
"""
ONLY_HISTORY = """  mLastResumedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
  mLastPausedActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
  mPausingActivity: ActivityRecord{aa1 u0 com.example.app/.MainActivity t12}
  mResumedActivity: null
"""


@pytest.mark.parametrize("dump", [DUMP_Q, DUMP_U, ACT_FG])
def test_resumed_record_variants_match(dump):
    assert is_foreground(dump, "com.example.app")


@pytest.mark.parametrize("dump", [DUMP_R_BACKGROUND, DUMP_U_BACKGROUND, ONLY_HISTORY, "", "garbage\n", ACT_BG])
def test_stale_or_missing_records_fail_closed(dump):
    assert not is_foreground(dump, "com.example.app")


def test_history_records_never_count_as_resumed():
    assert resumed_packages(ONLY_HISTORY) == set()
    assert resumed_packages(DUMP_R_BACKGROUND) == {"com.other.launcher"}


def test_top_resumed_record_decides_when_present():
    split = "  topResumedActivity=ActivityRecord{x u0 com.other.pip/.Pip t9}\n" + ACT_FG
    assert not is_foreground(split, "com.example.app")


def test_ambiguous_resumed_records_fail_closed():
    assert not is_foreground(ACT_FG + ACT_BG, "com.example.app")


@pytest.mark.parametrize("dump", [DUMP_R_BACKGROUND, DUMP_U_BACKGROUND, ONLY_HISTORY])
def test_keys_refused_on_stale_records(dump):
    fake = Fake(dump)
    with pytest.raises(ForegroundError):
        Device("tv", "com.example.app", runner=fake).send_keys(22)
    assert not any("input keyevent" in c[-1] for c in fake.calls)
