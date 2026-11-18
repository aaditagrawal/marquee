#!/usr/bin/env python3
"""Deterministic synthetic Jellyfin fixtures for Marquee's host (Robolectric) test suite.

Everything this script writes is invented: titles, overviews, people, studios, IDs, server and
user values, image tags and BlurHashes. The only inputs are the seed and the identity-free
technical specs in ``tools/fixtures/specs/`` (stream/codec/flag/URL-parameter structure of three
playback regressions). Standard library only.

    uv run --no-project python tools/fixtures/generate.py            # write app/src/test/resources
    uv run --no-project python tools/fixtures/generate.py --check    # verify committed files match

See tools/fixtures/README.md for field-level provenance and the sizing rationale.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import tempfile
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPECS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "specs")
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "test", "resources")
DEFAULT_SEED = 20261115
GENERATOR_VERSION = 1

SERVER_URL = "https://jellyfin.example"
SERVER_ID = "fixture-server"
USER_ID = "offline-home-tv"
DEVICE_ID = "marquee-fixture-device"
TICKS = 10_000_000  # Jellyfin ticks per second


# --------------------------------------------------------------------------------------------
# Deterministic primitives
# --------------------------------------------------------------------------------------------
class Ids:
    """32-hex IDs derived only from the seed, a namespace and a counter."""

    def __init__(self, seed: int):
        self.seed = seed
        self.n = 0

    def new(self, ns: str) -> str:
        self.n += 1
        return hashlib.sha256(f"marquee-fixture:{self.seed}:{ns}:{self.n}".encode()).hexdigest()[:32]


def dashed(h: str) -> str:
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def iso(day_offset: int, seconds: int = 0) -> str:
    """ISO timestamp counted from 2023-01-01 (synthetic calendar), Jellyfin's 7-digit format."""
    import datetime as dt
    t = dt.datetime(2023, 1, 1, tzinfo=dt.timezone.utc) + dt.timedelta(days=day_offset, seconds=seconds)
    return t.strftime("%Y-%m-%dT%H:%M:%S.0000000Z")


def premiere(year: int, rng: random.Random) -> str:
    return f"{year:04d}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}T00:00:00.0000000Z"


# --------------------------------------------------------------------------------------------
# BlurHash (valid by construction; decodes to soft synthetic gradients)
# --------------------------------------------------------------------------------------------
B83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"


def b83(value: int, length: int) -> str:
    return "".join(B83[(value // (83 ** (length - i - 1))) % 83] for i in range(length))


def blurhash(rng: random.Random) -> str:
    nx, ny = 4, 3
    r, g, b = rng.randint(30, 200), rng.randint(30, 200), rng.randint(30, 200)
    out = b83((nx - 1) + (ny - 1) * 9, 1) + b83(rng.randint(6, 14), 1) + b83((r << 16) + (g << 8) + b, 4)
    for _ in range(nx * ny - 1):
        out += b83(rng.randint(6, 12) * 361 + rng.randint(6, 12) * 19 + rng.randint(6, 12), 2)
    return out


# --------------------------------------------------------------------------------------------
# Invented vocabulary. Combinations are generated; none is meant to name a real work or person.
# --------------------------------------------------------------------------------------------
ADJ = ["Brass", "Copperline", "Quiet", "Velvet", "Hollow", "Lantern", "Paper", "Salt", "Amber", "Gilded", "Crooked",
       "Midnight", "Sleepless", "Patient", "Borrowed", "Seventh", "Northbound", "Distant", "Tidewater", "Glasshouse",
       "Lowland", "Winter", "Ochre", "Saffron", "Cobalt", "Thimble", "Harbour", "Marigold", "Kestrel"]
NOUN = ["Cartographer", "Lighthouse", "Ledger", "Signal", "Archive", "Ferry", "Workshop", "Observatory",
        "Almanac", "Courier", "Tailor", "Aviary", "Foundry", "Atlas", "Pavilion", "Clockmaker", "Greenhouse", "Telegraph",
        "Boathouse", "Quarry", "Carousel", "Bellfounder", "Apiary", "Saltworks", "Switchboard", "Monsoon", "Lamplighter"]
PLACE = ["Hollow Bay", "Lower Fenwick", "Port Ammerlie", "the Ninth Pier", "Calder Street", "Mossgate", "Vellin Ridge",
         "the Ashgrove Line", "Okora Island", "Little Pemberton", "the Glass Coast", "Saltmarsh Row"]
FIRST = ["Ilsa", "Teodor", "Mireille", "Kofi", "Anjali", "Haruto", "Seo-yeon", "Amara", "Bastien", "Lucía", "Oskar",
         "Priya", "Yusuf", "Nadia", "Emeric", "Ines", "Joaquín", "Mei", "Tobias", "Zara", "Ravi", "Hana", "Ciarán",
         "Leilani", "Matteo", "Saoirse", "Arjun", "Noor", "Felix", "Ottilie", "Dae-jung", "Imogen", "Kenji", "Ayla"]
SUR_A = ["Brack", "Ost", "Vell", "Quill", "Harrow", "Pemb", "Ashf", "Marr", "Dunst", "Corv", "Thistl", "Wend",
         "Fenn", "Lark", "Calv", "Rook", "Sorr", "Elm", "Hask", "Morv"]
SUR_B = ["well", "rand", "oran", "ingham", "ewood", "ecombe", "erly", "ington", "ow", "ard", "emere", "isford"]
JOBS = ["retired cartographer", "night-shift baker", "junior archivist", "ferry engineer", "piano tuner", "beekeeper",
        "radio operator", "lighthouse inspector", "museum restorer", "census clerk", "toy repairer", "glassblower"]
INCIDENT = ["finds a map that redraws itself every night", "inherits a workshop full of unfinished clocks",
            "receives letters postmarked twenty years in the future", "agrees to deliver one last parcel across the bay",
            "discovers the town's weather is being forecast a week early", "must win back a stolen recipe before the festival",
            "is asked to catalogue an archive that keeps growing", "starts hearing a radio station that went silent in 1962"]
TURN = ["Nothing about it is as simple as it looks.", "Old friends and older rivals return.",
        "The neighbours have opinions.", "A storm is coming, and so is the inspector.",
        "Every answer opens a smaller, stranger door.", "It will take the whole street to finish the job."]
TAGLINES = ["Every map has a margin.", "Some deliveries take a lifetime.", "Keep the lamp lit.",
            "The tide always comes back.", "Measure twice. Believe once."]
STUDIO_NAMES = ["Harrow Lane Pictures", "Northlight Fixture Films", "Paper Crane Studio", "Tidewater Works",
                "Lantern Yard Productions", "Saltmarsh Media", "Okora Island Television", "Copperline Animation"]

# Invented non-Latin titles (exercises Hangul, Devanagari, Tamil, kana and Han text rendering).
HANGUL = ["별빛 우체국", "종이달 세탁소", "이층집 로맨스", "한밤의 과수원 모임", "파도 소리 다방", "등대지기의 시계", "골목 끝 정원",
          "비 오는 날의 편지", "구름 사진관", "늦은 봄의 지도", "작은 섬 식당", "달빛 철도"]
DEVANAGARI = ["धूल और गरज", "चाँदनी गली", "काग़ज़ की नाव", "पुराना पुल", "बारिश का मौसम", "नीली खिड़की", "आख़िरी ट्रेन",
              "सात समंदर", "मिट्टी के रंग", "खोया हुआ नक्शा", "सुनहरी धूप", "शहर की रात"]
TAMIL = ["நிலா வீதி", "கடல் காற்று", "மழைக் கடிதம்", "பழைய பாலம்"]
KANA = ["雪あかり特急", "ほのおの猟犬 劇場版", "ひかりの図書館"]
LATIN_TRANSLIT = {"धूल और गरज": "Dust and Thunder"}


def plural(noun: str) -> str:
    if noun == "Atlas": return "Atlases"
    if noun.endswith("s"): return noun
    if noun.endswith("y") and noun[-2] not in "aeiou": return noun[:-1] + "ies"
    return noun + "s"


class Names:
    def __init__(self, rng: random.Random):
        self.rng = rng
        self.used: set[str] = set()

    def person(self) -> str:
        r = self.rng
        return f"{r.choice(FIRST)} {r.choice(SUR_A)}{r.choice(SUR_B)}"

    def title(self) -> str:
        r = self.rng
        for _ in range(200):
            p = weighted(r, [(0, 12), (1, 8), (2, 6), (3, 8), (4, 4), (5, 4), (6, 1), (7, 1)])[0]
            if p == 0: t = f"The {r.choice(ADJ)} {r.choice(NOUN)}"
            elif p == 1: t = f"{r.choice(NOUN)} of {r.choice(PLACE)}"
            elif p == 2: t = f"{r.choice(FIRST)}'s {r.choice(NOUN)}"
            elif p == 3: t = f"{r.choice(ADJ)} {plural(r.choice(NOUN))}"
            elif p == 4: t = f"{plural(r.choice(NOUN))} at {r.choice(PLACE)}"
            elif p == 5: t = f"{r.choice(ADJ)} {r.choice(NOUN)}: Part {r.choice(['Two', 'Three'])}"
            elif p == 6:
                adj = r.choice(ADJ)
                t = f"{'An' if adj[0] in 'AEIOU' else 'A'} {adj} Season in {r.choice(PLACE)}"
            else: t = f"{r.choice(['3', '7', '12', '40', '99', '1,000'])} {plural(r.choice(NOUN))}"
            if t not in self.used:
                self.used.add(t)
                return t
        raise RuntimeError("title space exhausted")

    def take(self, t: str) -> str:
        assert t not in self.used, t
        self.used.add(t)
        return t

    def overview(self) -> str:
        r = self.rng
        s = f"{r.choice(FIRST)}, a {r.choice(JOBS)} in {r.choice(PLACE)}, {r.choice(INCIDENT)}."
        for _ in range(r.randint(0, 2)):
            s += " " + r.choice(TURN)
        return s


# --------------------------------------------------------------------------------------------
# Stream templates. DisplayTitle/Title follow Jellyfin's formatting from technical fields only.
# --------------------------------------------------------------------------------------------
LANG = {"eng": "English", "kor": "Korean", "hin": "Hindi", "tam": "Tamil", "tel": "Telugu", "jpn": "Japanese",
        "zho": "Chinese", "fra": "French", "deu": "German", "spa": "Spanish", "swe": "Swedish", "ita": "Italian",
        "por": "Portuguese", "nld": "Dutch", "ara": "Arabic", "tha": "Thai", "ind": "Indonesian", "msa": "Malay",
        "rus": "Russian", "pol": "Polish", "tur": "Turkish", "ces": "Czech", "dan": "Danish", "fin": "Finnish",
        "nor": "Norwegian", "heb": "Hebrew", "ell": "Greek", "hun": "Hungarian", "ron": "Romanian", "vie": "Vietnamese",
        "und": "Undefined"}
AUDIO_NAMES = {"truehd": "Dolby TrueHD", "eac3": "Dolby Digital+", "ac3": "Dolby Digital", "dts": "DTS", "aac": "AAC",
               "opus": "OPUS", "flac": "FLAC", "mp3": "MP3"}
LAYOUT = {1: "mono", 2: "stereo", 6: "5.1", 8: "7.1"}

VIDEO = {
    # key: (codec, width, height, profile, range, rangeType, extra)
    "dv81": ("hevc", 3840, 2160, "Main 10", "HDR", "DOVIWithHDR10", {"DvProfile": 8, "ColorTransfer": "smpte2084"}),
    "dv81p": ("hevc", 3840, 1608, "Main 10", "HDR", "DOVIWithHDR10Plus", {"DvProfile": 8, "ColorTransfer": "smpte2084"}),
    "dv76": ("hevc", 3840, 2160, "Main 10", "HDR", "DOVIWithEL", {"DvProfile": 7, "ColorTransfer": "smpte2084"}),
    "dv5": ("hevc", 3840, 2160, "Main 10", "HDR", "DOVI", {"DvProfile": 5}),
    "doviinvalid": ("hevc", 3840, 2160, "Main 10", "HDR", "DOVIInvalid", {"ColorTransfer": "smpte2084"}),
    "hdr10": ("hevc", 3840, 2160, "Main 10", "HDR", "HDR10", {"ColorTransfer": "smpte2084"}),
    "hdr10p": ("hevc", 3840, 1600, "Main 10", "HDR", "HDR10Plus", {"ColorTransfer": "smpte2084"}),
    "hlg": ("hevc", 3840, 2160, "Main 10", "HDR", "HLG", {"ColorTransfer": "arib-std-b67"}),
    "hevc1080": ("hevc", 1920, 1080, "Main 10", "SDR", "SDR", {}),
    "hevc1080_8": ("hevc", 1920, 800, "Main", "SDR", "SDR", {}),
    "h264_1080": ("h264", 1920, 1080, "High", "SDR", "SDR", {}),
    "h264_816": ("h264", 1920, 816, "High", "SDR", "SDR", {}),
    "h264_720": ("h264", 1280, 720, "High", "SDR", "SDR", {}),
    "vp9": ("vp9", 1920, 1080, "Profile 0", "SDR", "SDR", {}),
    "av1hdr": ("av1", 1920, 960, "Main", "HDR", "HDR10", {"ColorTransfer": "smpte2084"}),
}
VIDEO_WEIGHTS = [("h264_1080", 30), ("hevc1080", 22), ("h264_816", 10), ("hevc1080_8", 8), ("dv81", 6), ("dv81p", 4),
                 ("hdr10", 6), ("hdr10p", 2), ("hlg", 2), ("dv76", 2), ("dv5", 1), ("doviinvalid", 1), ("h264_720", 3),
                 ("vp9", 2), ("av1hdr", 1)]
AUDIO = [("eac3", 6, "Dolby Digital Plus + Dolby Atmos", 3), ("eac3", 6, None, 14), ("ac3", 6, None, 14), ("aac", 2, "LC", 18),
         ("aac", 2, "HE-AAC", 4), ("truehd", 8, "Dolby TrueHD + Dolby Atmos", 3), ("dts", 6, "DTS-HD MA", 4), ("dts", 6, "DTS", 5),
         ("opus", 6, None, 2), ("flac", 2, None, 1), ("eac3", 2, None, 6)]
SUBS = [("subrip", 40), ("PGSSUB", 15), ("ass", 4), ("dvdsub", 6)]


def weighted(rng: random.Random, pairs):
    total = sum(w for *_, w in pairs)
    x = rng.randrange(total)
    for p in pairs:
        x -= p[-1]
        if x < 0:
            return p
    raise AssertionError


def video_display(codec: str, width: int, height: int, rtype: str, dv: int | None) -> str:
    res = "4K" if width >= 3200 else "1080p" if width >= 1800 else "720p" if width >= 1200 else "SD"
    name = {"hevc": "HEVC", "h264": "H264", "av1": "AV1", "vp9": "VP9"}.get(codec, codec.upper())
    if dv is not None:
        rng_name = {"DOVIWithHDR10": "Dolby Vision Profile 8.1 (HDR10)", "DOVIWithHDR10Plus": "Dolby Vision Profile 8.1 (HDR10+)",
                    "DOVIWithEL": "Dolby Vision Profile 7.6 (HDR10)", "DOVI": "Dolby Vision Profile 5"}.get(rtype, "Dolby Vision")
    else:
        rng_name = {"SDR": "SDR", "HDR10": "HDR10", "HDR10Plus": "HDR10+", "HLG": "HLG", "DOVIInvalid": "HDR"}.get(rtype, rtype)
    return f"{res} {name} {rng_name}"


def audio_display(codec: str, lang: str | None, channels: int | None, profile: str | None, default: bool, title: str | None) -> str:
    parts = []
    if title: parts.append(title)
    if lang: parts.append(LANG.get(lang, lang))
    parts.append(profile if profile and "Dolby" in profile or profile in ("DTS-HD MA", "HE-AAC") else AUDIO_NAMES.get(codec, codec.upper()))
    if channels: parts.append(LAYOUT.get(channels, f"{channels} ch"))
    if default: parts.append("Default")
    return " - ".join(parts)


def sub_display(codec: str, lang: str | None, default: bool, forced: bool, external: bool, title: str | None) -> str:
    parts = []
    if title: parts.append(title)
    if forced and not (title and "Forced" in title): parts.append("Forced")
    if lang: parts.append(LANG.get(lang, lang))
    if default: parts.append("Default")
    if external: parts.append("External")
    parts.append(codec.upper())
    return " - ".join(parts)


def video_stream(key: str, index: int, lang: str | None = None) -> dict:
    codec, w, h, profile, vrange, rtype, extra = VIDEO[key]
    s = {"Codec": codec}
    if lang: s["Language"] = lang
    s.update({"VideoRange": vrange, "VideoRangeType": rtype,
              "DisplayTitle": video_display(codec, w, h, rtype, extra.get("DvProfile")),
              "IsDefault": True, "IsForced": False, "Height": h, "Width": w, "Profile": profile,
              "Type": "Video", "Index": index, "IsExternal": False})
    return s


def audio_stream(index: int, lang: str, rng: random.Random, default: bool, spec=None, commentary: bool = False) -> dict:
    codec, channels, profile, _ = spec or weighted(rng, AUDIO)
    if commentary: codec, channels, profile = "aac", 2, "LC"
    title = "Commentary" if commentary else None
    s = {"Codec": codec, "Language": lang, "VideoRange": "Unknown", "VideoRangeType": "Unknown",
         "DisplayTitle": audio_display(codec, lang, channels, profile, default, title),
         "ChannelLayout": LAYOUT.get(channels, f"{channels} ch"), "Channels": channels,
         "IsDefault": default, "IsForced": False}
    if profile: s["Profile"] = profile
    s.update({"Type": "Audio", "Index": index, "IsExternal": False})
    if title: s["Title"] = title
    return s


def sub_stream(index: int, lang: str, rng: random.Random, default=False, forced=False, external=False, codec=None, title=None) -> dict:
    codec = codec or weighted(rng, SUBS)[0]
    s = {"Codec": codec, "Language": lang, "VideoRange": "Unknown", "VideoRangeType": "Unknown",
         "DisplayTitle": sub_display(codec, lang, default, forced, external, title),
         "IsDefault": default, "IsForced": forced, "Height": 1080 if codec == "PGSSUB" else 0,
         "Width": 1920 if codec == "PGSSUB" else 0, "Type": "Subtitle", "Index": index, "IsExternal": external}
    if title: s["Title"] = title
    return s


SUB_LANGS = ["eng", "fra", "spa", "deu", "ita", "por", "nld", "ara", "tha", "ind", "msa", "zho", "kor", "jpn", "hin",
             "rus", "pol", "tur", "ces", "dan", "fin", "nor", "swe", "heb", "ell", "hun", "ron", "vie"]


def media_streams(rng: random.Random, audio_langs: list[str], video_key: str | None = None, sub_count: int | None = None,
                  commentary: bool = False) -> list[dict]:
    streams = [video_stream(video_key or weighted(rng, VIDEO_WEIGHTS)[0], 0, rng.choice([None, "eng", "und"]))]
    for i, lang in enumerate(audio_langs):
        streams.append(audio_stream(len(streams), lang, rng, default=i == 0))
    if commentary:
        streams.append(audio_stream(len(streams), audio_langs[0], rng, False, commentary=True))
    if sub_count is None:
        sub_count = weighted(rng, [(0, 12), (1, 25), (2, 22), (3, 18), (4, 10), (6, 6), (9, 4), (14, 2), (30, 1), (52, 1)])[0]
    for j in range(sub_count):
        lang = "eng" if j < 2 else SUB_LANGS[(j * 7) % len(SUB_LANGS)]
        title = {0: None, 1: rng.choice(["SDH", None, "Forced"])}.get(j)
        streams.append(sub_stream(len(streams), lang, rng, default=False, forced=title == "Forced", title=title,
                                  external=j == sub_count - 1 and rng.random() < 0.08))
    return streams


# --------------------------------------------------------------------------------------------
# Library model
# --------------------------------------------------------------------------------------------
class Library:
    def __init__(self, seed: int):
        self.seed = seed
        self.rng = random.Random(seed)
        self.ids = Ids(seed)
        self.names = Names(random.Random(seed + 1))
        self.art = random.Random(seed + 2)
        self.people_pool = [(self.ids.new("person"), self.names.person()) for _ in range(260)]
        self.studios = [{"Name": n, "Id": self.ids.new("studio")} for n in STUDIO_NAMES]
        self.views = {
            "movies": self.ids.new("view"), "music": self.ids.new("view"), "tvshows": self.ids.new("view")}

    # --- small builders ---------------------------------------------------------------------
    def tag(self) -> str:
        return hashlib.sha256(f"tag:{self.seed}:{self.art.random()}".encode()).hexdigest()[:32]

    def images(self, kinds: list[str], backdrop: bool = True) -> dict:
        tags = {k: self.tag() for k in kinds}
        out = {"ImageTags": tags, "BackdropImageTags": [self.tag()] if backdrop else []}
        hashes = {k: {v: blurhash(self.art)} for k, v in tags.items()}
        if backdrop:
            hashes["Backdrop"] = {out["BackdropImageTags"][0]: blurhash(self.art)}
        out["ImageBlurHashes"] = hashes
        return out

    def people(self, actors: int, crew: list[str] = ("Director", "Writer"), portraits: float = 0.8) -> list[dict]:
        r = self.rng
        chosen = r.sample(self.people_pool, actors + len(crew))
        out = []
        for i, (pid, name) in enumerate(chosen):
            ptype = "Actor" if i < actors else crew[i - actors]
            p = {"Name": name, "Id": pid}
            if ptype == "Actor":
                p["Role"] = f"{r.choice(['', '', 'Captain ', 'Dr. ', 'Inspector '])}{r.choice(FIRST)} {r.choice(SUR_A)}{r.choice(SUR_B)}"
            p["Type"] = ptype
            if r.random() < portraits:
                tag = self.tag()
                p["PrimaryImageTag"] = tag
                p["ImageBlurHashes"] = {"Primary": {tag: blurhash(self.art)}}
            out.append(p)
        return out

    def chapters(self, runtime_ticks: int, count: int) -> list[dict]:
        return [{"StartPositionTicks": runtime_ticks * i // count, "Name": f"Chapter {i + 1}"} for i in range(count)]

    def userdata(self, played=False, position=0, favorite=False, played_pct=None, unplayed=None) -> dict:
        u = {}
        if played_pct is not None: u["PlayedPercentage"] = played_pct
        if unplayed is not None: u["UnplayedItemCount"] = unplayed
        u.update({"PlaybackPositionTicks": position, "PlayCount": 1 if played else 0, "IsFavorite": favorite, "Played": played})
        return u


ORIGIN_PLAN = {
    # origin: (movies, series, movie audio, series first-episode audio, movie locations)
    "English": (125, 18, ["eng"], "eng", ["United States of America"]),
    "BritishLoc": (4, 4, ["eng"], "eng", ["United Kingdom"]),
    "Indian": (30, 9, ["hin"], "hin", ["India"]),
    "Korean": (5, 7, ["kor"], "kor", ["South Korea"]),
    "Japanese": (1, 2, ["jpn"], "jpn", ["Japan"]),
    "Chinese": (1, 1, ["zho"], "zho", ["China"]),
    "French": (2, 0, ["fra"], None, ["France"]),
    "German": (1, 0, ["deu"], None, ["Germany"]),
    "Spanish": (2, 0, ["spa"], None, ["Spain"]),
    "Scandinavian": (1, 0, ["swe"], None, ["Sweden"]),
    "Unknown": (0, 4, [], None, []),
}
GENRE_TARGETS = [  # CatalogIndex shelf -> (member count, genre spellings)
    ("Action & Adventure", 72, ["Action", "Adventure"]), ("Sci-Fi & Fantasy", 65, ["Science Fiction", "Fantasy"]),
    ("Comedy", 56, ["Comedy"]), ("Thriller & Mystery", 51, ["Thriller", "Mystery"]),
    ("Animation & Family", 38, ["Animation", "Family"]), ("Crime", 26, ["Crime"]), ("Romance", 17, ["Romance"]),
    ("Documentary", 14, ["Documentary"])]
RATINGS = ["PG", "PG-13", "R", "TV-MA", "TV-14", "IN-U/A 13+", "IN-U/A 16+", "IN-A", "15", "12", "U"]


def build(seed: int) -> dict[str, object]:
    L = Library(seed)
    r = L.rng
    N = L.names
    files: dict[str, object] = {}

    # Named fixtures the tests address by role (see README "Named roles").
    SERIES_NAME = N.take("The Midnight Orchard Society")
    SERIES_ORIGINAL = "한밤의 과수원 모임"
    MOVIE_NAME = N.take("The Brass Cartographer")
    HERO_SERIES = N.take("Driftwood")
    named = {
        "korean_series_script_a": (SERIES_NAME, SERIES_ORIGINAL),
        "korean_series_script_b": (N.take("Paper Moon Laundry"), "종이달 세탁소"),
        "korean_series_script_c": (N.take("Second Floor Romance"), "이층집 로맨스"),
        "japanese_series_script": (N.take("Snowlight Express"), "雪あかり特急"),
        "japanese_movie_audio": (N.take("Ember Hound: The Movie"), "ほのおの猟犬 劇場版"),
        "indian_movie_mislabeled_audio": (N.take("Dust and Thunder"), "धूल और गरज"),
        "indian_series_episode_audio": (N.take("Ledger of Small Fortunes"), "Ledger of Small Fortunes"),
        "english_series_episode_audio": (HERO_SERIES, HERO_SERIES),
        "british_series_location": (N.take("Inspector Wren"), "Inspector Wren"),
        "chinese_series_location": (N.take("Ink Between Pages"), "Ink Between Pages"),
    }
    # Long and non-Latin display names for layout stress.
    long_names = [N.take("The Unreasonably Long Afternoon of the Copperline Lighthouse Keeper's Apprentice"),
                  N.take("Saltmarsh Row: A Very Patient History of Small Boats, Tall Tales and the People Who Mend Them")]

    movies: list[dict] = []
    series: list[dict] = []
    first_audio: dict[str, list] = {}
    day = 0

    def base(kind: str, name: str, original: str | None, year: int) -> dict:
        nonlocal day
        day += r.randint(1, 4)
        item = {"Name": name}
        if original is not None: item["OriginalTitle"] = original
        item["Id"] = L.ids.new(kind)
        item["DateCreated"] = iso(day, r.randrange(86400))
        return item

    plan = []
    for origin, (m, s, *_rest) in ORIGIN_PLAN.items():
        plan += [(origin, "Movie")] * m + [(origin, "Series")] * s
    r.shuffle(plan)

    def pick_name(origin: str, kind: str):
        for key, (nm, ot) in list(named.items()):
            want = {"korean_series_script_a": ("Korean", "Series"), "korean_series_script_b": ("Korean", "Series"),
                    "korean_series_script_c": ("Korean", "Series"), "japanese_series_script": ("Japanese", "Series"),
                    "japanese_movie_audio": ("Japanese", "Movie"), "indian_movie_mislabeled_audio": ("Indian", "Movie"),
                    "indian_series_episode_audio": ("Indian", "Series"), "english_series_episode_audio": ("English", "Series"),
                    "british_series_location": ("BritishLoc", "Series"), "chinese_series_location": ("Chinese", "Series")}[key]
            if want == (origin, kind):
                del named[key]
                return nm, ot, key
        if origin == "Korean":
            ot = HANGUL[(len(movies) + len(series)) % len(HANGUL)]
            while ot in (SERIES_ORIGINAL, "종이달 세탁소", "이층집 로맨스"):
                ot = HANGUL[(HANGUL.index(ot) + 1) % len(HANGUL)]
            return (ot if r.random() < 0.5 else N.title()), ot, None
        if origin == "Indian":
            script = DEVANAGARI if r.random() < 0.8 else TAMIL
            ot = script[r.randrange(len(script))]
            if ot == "धूल और गरज": ot = "चाँदनी गली"
            return (ot if r.random() < 0.35 else N.title()), (ot if kind == "Movie" else None), None
        if origin == "Japanese":
            return "ひかりの図書館", "ひかりの図書館", None
        t = long_names.pop() if long_names and r.random() < 0.05 else N.title()
        return t, t, None

    for origin, kind in plan:
        _m, _s, maudio, saudio, locs = ORIGIN_PLAN[origin]
        name, original, role = pick_name(origin, kind)
        year = r.choice(range(1958, 2027)) if r.random() < 0.3 else r.choice(range(2012, 2027))
        if role == "korean_series_script_a": year = 2026
        item = base("item", name, original, year)
        item["_role"] = role
        item["_origin"] = origin
        item["_kind"] = kind
        if kind == "Movie":
            runtime = r.randint(80, 175) * 60 * TICKS + r.randint(0, 59) * TICKS
            langs = list(maudio)
            if role == "indian_movie_mislabeled_audio": langs = ["eng"]          # mislabeled encoder tag
            elif origin == "Indian" and r.random() < 0.3: langs = langs + ["eng"]
            elif origin == "English" and r.random() < 0.15: langs = langs + [r.choice(["fra", "spa", "deu", "ita"])]
            if origin == "Indian" and r.random() < 0.3: langs[0] = r.choice(["tam", "tel"]) if langs[0] == "hin" else langs[0]
            streams = media_streams(r, langs, commentary=origin == "English" and r.random() < 0.08)
            video = streams[0]
            bitrate = {"4K": r.randint(14, 60), "1080p": r.randint(3, 14), "720p": r.randint(2, 5)}.get(
                video["DisplayTitle"].split(" ")[0], 4) * 1_000_000 + r.randint(0, 999) * 1000
            container = "mkv" if r.random() < 0.85 else "mp4"
            item["Container"] = container
            item["PremiereDate"] = premiere(year, r)
            item["MediaSources"] = [{"Id": item["Id"], "Type": "Default", "Container": container,
                                     "Size": bitrate * (runtime // TICKS) // 8, "Name": name, "RunTimeTicks": runtime,
                                     "MediaStreams": streams, "Bitrate": bitrate}]
            if r.random() < 0.82: item["CriticRating"] = r.randint(20, 99)
            item["ProductionLocations"] = list(locs) + ([r.choice(["Germany", "Canada", "France"])] if r.random() < 0.1 else [])
            if origin == "BritishLoc": item["ProductionLocations"] = ["United Kingdom"]
            item["_runtime"] = runtime
            movies.append(item)
        else:
            item["PremiereDate"] = premiere(year, r)
            if origin in ("BritishLoc", "Chinese"): item["ProductionLocations"] = list(locs)
            if saudio:
                first_audio[item["Id"]] = [{"Codec": r.choice(["eac3", "aac", "ac3"]), "Language": saudio,
                                            "DisplayTitle": f"{LANG[saudio]} - Default", "IsDefault": True,
                                            "Type": "Audio", "Index": 1, "Channels": 2}]
            item["_runtime"] = r.randint(22, 62) * 60 * TICKS
            series.append(item)
    assert not named, f"unassigned named roles: {named}"
    assert len(movies) == 172 and len(series) == 45
    # Guaranteed stress sources: very long subtitle lists and an invalid Dolby Vision signal.
    for it, video, subs in zip([m for m in movies if m["_origin"] == "English" and not m["_role"]][-3:],
                               ["hevc1080", "h264_1080", "doviinvalid"], [52, 30, 3]):
        streams = media_streams(r, ["eng", "fra"], video_key=video, sub_count=subs)
        it["MediaSources"][0]["MediaStreams"] = streams

    catalog = movies + series
    series_role = next(s for s in series if s["_role"] == "korean_series_script_a")
    movie = next(m for m in movies if m["_origin"] == "English" and not m["_role"])
    fixed_genres = {series_role["Id"]: ["Sci-Fi & Fantasy", "Comedy", "Drama"], movie["Id"]: ["Comedy", "Adventure", "Crime"]}
    shelf_of = {g: shelf for shelf, _n, spellings in GENRE_TARGETS for g in spellings + [shelf]}
    # Exact shelf membership counts (CatalogIndex genre shelves), then ratings.
    genre_lists = {it["Id"]: list(fixed_genres.get(it["Id"], [])) for it in catalog}
    free = [c for c in catalog if c["Id"] not in fixed_genres]
    for _shelf, count, spellings in GENRE_TARGETS:
        already = sum(1 for g in fixed_genres.values() if any(shelf_of.get(x) == _shelf for x in g))
        for it in r.sample(free, count - already):
            g = r.choice(spellings)
            if it["_kind"] == "Series" and _shelf in ("Action & Adventure", "Sci-Fi & Fantasy") and r.random() < 0.5:
                g = _shelf
            genre_lists[it["Id"]].append(g)
    for it in free:
        if not genre_lists[it["Id"]] or r.random() < 0.4: genre_lists[it["Id"]].append("Drama")
    rated_pool = [c for c in catalog if c["Id"] not in (series_role["Id"], movie["Id"])]
    rated_ids = set(it["Id"] for it in r.sample(rated_pool, 103)) | {series_role["Id"]}
    unrated = set(it["Id"] for it in r.sample([c for c in rated_pool if c["Id"] not in rated_ids], 10))
    no_overview = set(it["Id"] for it in r.sample(catalog, 5))
    no_official = set(it["Id"] for it in r.sample(catalog, 21))
    no_primary = {r.choice([m for m in movies if m is not movie])["Id"]}
    no_logo = set(it["Id"] for it in r.sample(rated_pool, 15))
    no_thumb = set(it["Id"] for it in r.sample(rated_pool, 20))
    no_original = set(it["Id"] for it in r.sample([c for c in catalog if c["_origin"] in ("English", "Unknown") and not c["_role"]], 3))

    for it in catalog:
        iid = it["Id"]
        is_movie = it["_kind"] == "Movie"
        out = it
        if iid in no_original: out.pop("OriginalTitle", None)
        if iid not in no_official: out["OfficialRating"] = r.choice(RATINGS)
        if iid not in no_overview: out["Overview"] = N.overview()
        out["Taglines"] = [r.choice(TAGLINES)] if r.random() < 0.3 else []
        out["Genres"] = genre_lists[iid]
        if iid in rated_ids: out["CommunityRating"] = round(r.uniform(7.5, 9.6), 3)
        elif iid not in unrated: out["CommunityRating"] = round(r.uniform(4.2, 7.49), 3)
        if it["_role"] == "korean_series_script_a": out["CommunityRating"] = 9.2
        out["RunTimeTicks"] = it["_runtime"]
        out["ProductionYear"] = int(out["PremiereDate"][:4])
        out["IsFolder"] = not is_movie
        out["Type"] = "Movie" if is_movie else "Series"
        # Catalog cards never show cast; keep a realistic spread (incl. a 30-person cap) at lower volume.
        actors = 30 if r.random() < 0.02 else min(30, max(0, int(r.gauss(5, 3))))
        out["People"] = L.people(actors, ["Director", "Writer"] if r.random() < 0.85 else ["Director"], portraits=0.3)
        out["Studios"] = r.sample(L.studios, r.randint(0, 2))
        unplayed = r.randint(0, 24) if not is_movie else None
        out["UserData"] = L.userdata(played=r.random() < 0.18, favorite=r.random() < 0.06,
                                     played_pct=None, unplayed=unplayed)
        out["PrimaryImageAspectRatio"] = 0.6666666666666666
        if is_movie:
            out["MediaStreams"] = out["MediaSources"][0]["MediaStreams"]
        kinds = [k for k, miss in (("Primary", no_primary), ("Logo", no_logo), ("Thumb", no_thumb)) if iid not in miss]
        out.update(L.images(kinds))
        if is_movie:
            out["Chapters"] = L.chapters(it["_runtime"], r.randint(0, 6))
            out["MediaType"] = "Video"
            video = out["MediaStreams"][0]
            out["Width"], out["Height"] = video["Width"], video["Height"]
        else:
            out["MediaType"] = "Unknown"
            out["ChildCount"] = r.randint(1, 6)
            out["Status"] = r.choice(["Continuing", "Ended"])
            out["EndDate"] = premiere(min(2026, out["ProductionYear"] + r.randint(0, 6)), r)
    # The detail series: one season, eleven episodes, continuing, 2026.
    hero_series = next(s for s in series if s["_role"] == "english_series_episode_audio")
    series_item = series_role
    series_item.update({"ProductionYear": 2026, "PremiereDate": "2026-04-06T00:00:00.0000000Z",
                        "EndDate": "2026-06-15T00:00:00.0000000Z", "OfficialRating": "TV-14", "Status": "Continuing",
                        "ChildCount": 1, "RunTimeTicks": 0, "Genres": ["Sci-Fi & Fantasy", "Comedy", "Drama"],
                        "Overview": "When a sleepless orchard keeper in Lower Fenwick finds the trees whispering next week's "
                                    "weather, she recruits a retired radio operator, a nervous census clerk and two rival "
                                    "bakers to keep the secret long enough to save the autumn festival."})
    series_item["People"] = L.people(8, ["Director", "Writer"])
    series_item["Studios"] = L.studios[6:8]
    series_item["UserData"] = L.userdata(played_pct=90.9, unplayed=1)
    series_item.update(L.images(["Logo", "Thumb", "Primary"]))

    # Player specs provide the technical shape for three regressions; map their tokens.
    specs = {n: json.load(open(os.path.join(SPECS, f"{n}.spec.json"), encoding="utf-8"))
             for n in ("player-audio-remux", "player-direct-regression", "player-playback-info")}
    # The detail movie reuses the Dolby Vision P8.1 regression's stream layout.
    spec_movie = specs["player-direct-regression"][0]["item"]
    movie.update({"Name": MOVIE_NAME, "OriginalTitle": MOVIE_NAME, "ProductionYear": 2025,
                  "PremiereDate": "2025-08-22T00:00:00.0000000Z", "CommunityRating": 6.4, "CriticRating": 71,
                  "OfficialRating": "PG-13",
                  "Taglines": ["Every coastline is an argument."],
                  "ProductionLocations": ["United States of America", "Germany"],
                  "Overview": "Two rival mapmakers are hired by the same island council to chart a harbour that "
                              "keeps moving overnight. With one brass compass between them and a deadline set by the "
                              "tide, they argue their way along every inlet of Okora Island."})
    movie_streams = [render_stream(s) for s in spec_movie["MediaSources"][0]["MediaStreams"]]
    movie["MediaSources"][0]["MediaStreams"] = movie_streams
    movie["MediaStreams"] = movie_streams
    movie["Width"], movie["Height"] = movie_streams[0]["Width"], movie_streams[0]["Height"]
    movie["MediaSources"][0].update({"Name": MOVIE_NAME, "Container": "mkv", "Bitrate": 25_600_000,
                                     "Size": 25_600_000 * (movie["_runtime"] // TICKS) // 8})
    movie["Container"] = "mkv"
    movie["People"] = L.people(15, ["Director", "Writer", "Writer"] + ["Producer"] * 9)
    movie["Studios"] = L.studios[0:3]
    movie.update(L.images(["Thumb", "Primary", "Logo"]))

    def strip(it):
        return {k: v for k, v in it.items() if not k.startswith("_")}

    # Order and finalise. Recently added is newest first; the catalog file mirrors the API's A-Z order.
    catalog.sort(key=lambda it: (it["Name"].lower(), it["Id"]))
    cat_items = [strip(it) for it in catalog]
    files["offline/catalog.json"] = {"Items": cat_items, "TotalRecordCount": len(cat_items), "StartIndex": 0}
    files["offline/first-episode-audio.json"] = dict(sorted(first_audio.items()))

    movie_detail = strip(movie)
    movie_detail = insert_after(movie_detail, "Type", "ParentId", L.views["movies"])
    movie_detail["LocalTrailerCount"] = 1
    movie_detail["SpecialFeatureCount"] = 0
    files["offline/movie.json"] = movie_detail

    series_detail = strip(series_item)
    series_detail = insert_after(series_detail, "Type", "ParentId", L.views["tvshows"])
    series_detail["LocalTrailerCount"] = 0
    series_detail["SpecialFeatureCount"] = 0
    files["offline/series.json"] = series_detail

    # --- the series' single season and its eleven episodes ----------------------------------
    season_id = L.ids.new("season")
    season = {"Name": "Season 1", "Id": season_id, "DateCreated": series_item["DateCreated"],
              "PremiereDate": series_item["PremiereDate"], "Taglines": [], "Genres": [], "ProductionYear": 2026,
              "IndexNumber": 1, "IsFolder": True, "Type": "Season", "People": series_item["People"][:10], "Studios": [],
              "ParentLogoItemId": series_item["Id"], "ParentBackdropItemId": series_item["Id"],
              "ParentBackdropImageTags": series_item["BackdropImageTags"],
              "UserData": L.userdata(played_pct=90.9, unplayed=1), "ChildCount": 11, "SeriesName": SERIES_NAME,
              "SeriesId": series_item["Id"], "PrimaryImageAspectRatio": 0.6666666666666666,
              "SeriesPrimaryImageTag": series_item["ImageTags"]["Primary"]}
    season.update(L.images(["Primary"], backdrop=False))
    season.update({"ParentLogoImageTag": series_item["ImageTags"]["Logo"], "ParentThumbItemId": series_item["Id"],
                   "ParentThumbImageTag": series_item["ImageTags"]["Thumb"], "MediaType": "Unknown"})
    files["offline/series-seasons.json"] = {"Items": [season], "TotalRecordCount": 1, "StartIndex": 0}

    spec_episode = specs["player-direct-regression"][2]["item"]  # H.264 + E-AC-3 Korean episode layout
    episode_streams = [render_stream(s) for s in spec_episode["MediaSources"][0]["MediaStreams"]]
    episode_titles = ["The First Frost", "Whispering Rows", "A Ledger of Apples", "Two Bakers, One Oven",
                      "The Radio in the Cellar", "Census Day", "Harvest Moon Rehearsal", "Static on the Line",
                      "The Inspector's Umbrella", "Forecast for Tuesday", "Episode 11"]
    episodes = []
    for i, runtime_ms in enumerate(SEGMENT_RUNTIMES_MS):
        eid = L.ids.new("episode")
        runtime = runtime_ms * 10_000
        played = i < 10
        ep = {"Name": episode_titles[i], "Id": eid, "DateCreated": iso(1200 + i * 7, 3600 * 20),
              "Container": "mkv", "PremiereDate": f"2026-{5 + i // 4:02d}-{1 + (i % 4) * 7:02d}T00:00:00.0000000Z",
              "MediaSources": [{"Id": eid, "Type": "Default", "Container": "mkv", "Size": 1_550_000 * (runtime_ms // 1000) // 8 * 8,
                                "Name": f"{SERIES_NAME} S01E{i + 1:02d}", "RunTimeTicks": runtime,
                                "MediaStreams": episode_streams, "Bitrate": 5_020_000}],
              "Overview": N.overview(), "Taglines": [], "Genres": ["Sci-Fi & Fantasy", "Comedy", "Drama"],
              "RunTimeTicks": runtime, "ProductionYear": 2026, "IndexNumber": i + 1, "ParentIndexNumber": 1,
              "IsFolder": False, "Type": "Episode",
              "People": series_item["People"][:8] + (L.people(7, []) if i != 4 else []),
              "Studios": series_item["Studios"], "ParentLogoItemId": series_item["Id"],
              "ParentBackdropItemId": series_item["Id"], "ParentBackdropImageTags": series_item["BackdropImageTags"],
              "UserData": L.userdata(played=played), "SeriesName": SERIES_NAME, "SeriesId": series_item["Id"],
              "SeasonId": season_id, "PrimaryImageAspectRatio": 1.7777777777777777,
              "SeriesPrimaryImageTag": series_item["ImageTags"]["Primary"], "SeasonName": "Season 1",
              "MediaStreams": episode_streams}
        ep.update(L.images(["Primary"], backdrop=False))
        ep.update({"ParentLogoImageTag": series_item["ImageTags"]["Logo"], "ParentThumbItemId": series_item["Id"],
                   "ParentThumbImageTag": series_item["ImageTags"]["Thumb"],
                   "Chapters": L.chapters(runtime, 6), "MediaType": "Video", "Width": 1920, "Height": 1080})
        episodes.append(ep)
    files["offline/series-episodes.json"] = {"Items": episodes, "TotalRecordCount": len(episodes), "StartIndex": 0}
    files["offline/series-nextup.json"] = {"Items": [episodes[-1]], "TotalRecordCount": 1, "StartIndex": 0}

    # Segment regression: technical timings preserved; IDs synthetic.
    seg_rows, next_rows = [], []
    for i, (ep, segs) in enumerate(zip(episodes, SEGMENTS)):
        seg_rows.append({"item": ep["Id"], "episode": i + 1, "runtime_ms": SEGMENT_RUNTIMES_MS[i],
                         "segments": [{"Id": L.ids.new("segment"), "ItemId": ep["Id"], "Type": t, "StartTicks": a,
                                       "EndTicks": b} for t, a, b in segs]})
        nxt = episodes[i + 1]["Id"] if i + 1 < len(episodes) else None
        next_rows.append({"itemId": ep["Id"], "episode": i + 1,
                          "returnedEpisodes": [i + 1, i + 2] if nxt else [i + 1], "nextEpisode": i + 2 if nxt else None,
                          "nextItemId": nxt})
    files["series-segments.json"] = seg_rows
    files["series-next-episodes.json"] = next_rows

    # --- Continue Watching / Next Up ---------------------------------------------------------
    def episode_of(show: dict, season_no: int, number: int, name: str, rating, position_pct=None) -> dict:
        eid = L.ids.new("episode")
        runtime = show["_runtime"] if "_runtime" in show else 45 * 60 * TICKS
        streams = media_streams(r, ["eng" if show is hero_series else r.choice(["eng", "kor", "hin"])], sub_count=r.randint(1, 8))
        sid = L.ids.new("season")
        position = int(runtime * position_pct / 100) if position_pct else 0
        ep = {"Name": name, "Id": eid, "DateCreated": iso(1300 + number, 7200), "Container": "mkv",
              "PremiereDate": premiere(show["ProductionYear"], r),
              "MediaSources": [{"Id": eid, "Type": "Default", "Container": "mkv", "Size": 4_000_000 * (runtime // TICKS) // 8,
                                "Name": f"{show['Name']} S{season_no:02d}E{number:02d}", "RunTimeTicks": runtime,
                                "MediaStreams": streams, "Bitrate": 4_000_000}],
              "Overview": N.overview(), "Taglines": [], "Genres": show["Genres"]}
        if rating is not None: ep["CommunityRating"] = rating
        ep.update({"RunTimeTicks": runtime, "ProductionYear": show["ProductionYear"], "IndexNumber": number,
                   "ParentIndexNumber": season_no, "IsFolder": False, "Type": "Episode",
                   "People": show["People"][:r.randint(6, 15)], "Studios": show["Studios"],
                   "ParentLogoItemId": show["Id"], "ParentBackdropItemId": show["Id"],
                   "ParentBackdropImageTags": show["BackdropImageTags"],
                   "UserData": L.userdata(position=position, played_pct=position_pct),
                   "SeriesName": show["Name"], "SeriesId": show["Id"], "SeasonId": sid,
                   "PrimaryImageAspectRatio": 1.7777777777777777,
                   "SeriesPrimaryImageTag": show["ImageTags"].get("Primary", L.tag()), "SeasonName": f"Season {season_no}",
                   "MediaStreams": streams})
        ep.update(L.images(["Primary"], backdrop=False))
        ep["ParentLogoImageTag"] = show["ImageTags"].get("Logo", L.tag())
        if "Thumb" in show["ImageTags"]:
            ep["ParentThumbItemId"] = show["Id"]; ep["ParentThumbImageTag"] = show["ImageTags"]["Thumb"]
        ep.update({"Chapters": L.chapters(runtime, 5), "MediaType": "Video", "Width": streams[0]["Width"],
                   "Height": streams[0]["Height"]})
        return ep

    other_series = [s for s in series if s not in (series_item, hero_series) and "Logo" in s["ImageTags"]]
    resume_movies = [m for m in movies if m is not movie and "Thumb" in m["ImageTags"] and "Logo" in m["ImageTags"]][:2]
    def pct() -> float:
        return round(r.uniform(12, 70), 1)

    def resumed(m: dict) -> dict:
        p = pct()
        return dict(strip(m), UserData=L.userdata(position=int(m["_runtime"] * p / 100), played_pct=p))

    resume = [
        episode_of(hero_series, 3, 4, "Low Water at Pier Nine", 5.24, pct()),
        resumed(resume_movies[0]),
        episode_of(other_series[0], 1, 6, "Inventory of Small Bells", 7.1, pct()),
        episode_of(other_series[1], 1, 2, "Beekeeping for Beginners", 9.4, pct()),
        episode_of(other_series[2], 1, 9, "The Weathervane Accord", 8.2, pct()),
        resumed(resume_movies[1]),
        episode_of(other_series[3], 2, 3, "Overnight at the Switchboard", 6.9, pct()),
    ]
    nextup = [episodes[-1],
              episode_of(hero_series, 3, 5, "Tide Tables", 7.6),
              episode_of(other_series[4], 1, 3, "Fourteen Umbrellas", None),
              episode_of(other_series[5], 1, 1, "Episode 1", 8.8),
              episode_of(other_series[6], 1, 4, "The Long Way Round the Quarry", 7.9)]
    files["offline/resume.json"] = {"Items": resume, "TotalRecordCount": len(resume), "StartIndex": 0}
    files["offline/nextup.json"] = {"Items": nextup, "TotalRecordCount": len(nextup), "StartIndex": 0}

    views = []
    for name, ctype in (("Movies", "movies"), ("Music", "music"), ("Shows", "tvshows")):
        v = {"Name": name, "Id": L.views[ctype], "DateCreated": iso(0), "Taglines": [], "Genres": [], "IsFolder": True,
             "ParentId": L.ids.new("root"), "Type": "CollectionFolder", "People": [], "Studios": [], "LocalTrailerCount": 0,
             "UserData": L.userdata(), "ChildCount": {"movies": 172, "music": 0, "tvshows": 45}[ctype],
             "SpecialFeatureCount": 0, "PrimaryImageAspectRatio": 1.7777777777777777, "CollectionType": ctype}
        tag = L.tag()
        v.update({"ImageTags": {"Primary": tag}, "BackdropImageTags": [], "ImageBlurHashes": {"Primary": {tag: blurhash(L.art)}},
                  "MediaType": "Unknown"})
        views.append(v)
    files["offline/views.json"] = {"Items": views, "TotalRecordCount": 3, "StartIndex": 0}
    recent = sorted(cat_items, key=lambda it: it["DateCreated"], reverse=True)[:20]
    files["offline/home.json"] = {
        "userId": USER_ID, "views": views,
        "rows": [{"key": "resume", "title": "Continue Watching", "items": resume, "wide": True},
                 {"key": "nextup", "title": "Next Up", "items": nextup, "wide": True},
                 {"key": "recent", "title": "Recently Added", "items": recent}],
        "savedAt": 1790000000000}

    # --- Small Home navigation fixture: 8 rows x 12 cards, card fields only, no artwork tags ---
    def nav_card(it: dict, position: int = 0) -> dict:
        c = {"Id": it["Id"], "Name": it["Name"], "Type": it["Type"], "IsFolder": it["IsFolder"], "MediaType": "Video"}
        for k in ("Overview", "Genres", "ProductionYear", "RunTimeTicks", "OfficialRating", "CommunityRating"):
            if k in it: c[k] = it[k]
        c["UserData"] = {"PlaybackPositionTicks": position, "Played": False}
        if it["Type"] == "Series": c["ChildCount"] = it.get("ChildCount", 1)
        return c

    nav_rows = []
    by_origin = lambda o: [it for it in catalog if it["_origin"] == o]
    sources = [("resume", "Continue Watching", True, movies[0:12]),
               ("nextup", "Next Up", True, movies[12:24]),
               ("recent", "Recently Added", False, movies[24:36]),
               ("korean", "Korean Shows & Movies", False, (by_origin("Korean") + by_origin("Japanese"))[:12]),
               ("indian", "Hindi & Indian Cinema", False, by_origin("Indian")[:12]),
               ("english", "Hollywood / English", False, [m for m in by_origin("English") if m["Type"] == "Series"][:12]),
               ("rated", "Top Rated", False, [m for m in movies[36:] if m["Id"] in rated_ids][:12]),
               ("comedy", "Comedy", False, [m for m in movies[60:] if "Comedy" in m["Genres"]][:12])]
    for key, title, wide, items in sources:
        assert len(items) == 12, (key, len(items))
        row = {"key": key, "title": title,
               "items": [nav_card(it, 1_200_000_000 if key == "resume" else 0) for it in items]}
        if wide: row["wide"] = True
        nav_rows.append(row)
    files["home-navigation.json"] = {
        "userId": "fixture", "serverUrl": "", "savedAt": 1790000000000,
        "views": [{"Id": "movies", "Name": "Movies", "Type": "UserView", "CollectionType": "movies"},
                  {"Id": "shows", "Name": "Shows", "Type": "UserView", "CollectionType": "tvshows"}],
        "rows": nav_rows}

    # --- Player regressions from identity-free specs ------------------------------------------
    token_ids: dict[str, str] = {}
    token_names: dict[str, str] = {}

    def id_for(token: str) -> str:
        if token not in token_ids:
            token_ids[token] = L.ids.new("player")
        return token_ids[token]

    # Map the DV P8.1 regression to the detail movie and the Korean episode to the detail series.
    token_ids[spec_movie["Id"]] = movie["Id"]
    token_names[spec_movie["$name"]] = MOVIE_NAME
    token_ids[spec_episode["Id"]] = episodes[0]["Id"]
    token_ids[spec_episode["SeriesId"]] = series_item["Id"]
    token_names[spec_episode["$name"]] = episodes[0]["Name"]
    token_names[spec_episode["$series"]] = SERIES_NAME
    player_titles = iter(["Signal Over Tidewater", "The Glasshouse Telegraph", "Northbound Saltworks",
                          "Kestrel Switchboard", "Lamplighters of Mossgate", "Ochre Quarry"])

    def name_for(token: str) -> str:
        if token not in token_names:
            token_names[token] = N.take(next(player_titles))
        return token_names[token]

    def hydrate(obj):
        if isinstance(obj, list):
            return [hydrate(x) for x in obj]
        if isinstance(obj, dict):
            return {k: hydrate(v) for k, v in obj.items()}
        if isinstance(obj, str) and "$" in obj:
            return substitute(obj)
        return obj

    def substitute(s: str) -> str:
        import re
        def rep(m):
            kind, n = m.group(1), m.group(2)
            tok = f"${kind}:{n}"
            if kind == "ID":
                return id_for(tok)
            if kind == "SESSION": return hashlib.sha256(f"session:{seed}:{n}".encode()).hexdigest()[:32]
            if kind == "TAG": return hashlib.sha256(f"etag:{seed}:{n}".encode()).hexdigest()[:32]
            if kind == "NAME": return name_for(tok)
            raise KeyError(tok)
        s = s.replace("$DEVICE", DEVICE_ID).replace("$SERVER", SERVER_URL).replace("$USER", USER_ID)
        # Stream/delivery URL paths use the dashed GUID spelling for the item segment, like the server does.
        s = re.sub(r"(?<=/[Vv]ideos/)\$ID:(\d+)", lambda m: dashed(id_for(f"$ID:{m.group(1)}")), s)
        return re.sub(r"\$(ID|SESSION|TAG|NAME):(\d+)", rep, s)

    def player_item(spec: dict) -> dict:
        it = {}
        it["Name"] = name_for(spec["$name"])
        it["ServerId"] = SERVER_ID
        it["Id"] = id_for(spec["Id"])
        for k in ("HasSubtitles", "Container"):
            if k in spec: it[k] = spec[k]
        it["PremiereDate"] = "2026-03-15T00:00:00.0000000Z"
        sources = []
        for ms in spec["MediaSources"]:
            src = hydrate({k: v for k, v in ms.items() if k not in ("MediaStreams", "Id")})
            src = {"Protocol": src.pop("Protocol", "File"), "Id": id_for(ms["Id"]),
                   "Path": f"/media/synthetic/{id_for(ms['Id'])}.{ms.get('Container', 'mkv').split(',')[0]}",
                   **src, "Name": it["Name"], "ETag": hashlib.sha256(f"etag:{seed}:{ms['Id']}".encode()).hexdigest()[:32],
                   "MediaStreams": [render_stream(hydrate(s)) for s in ms["MediaStreams"]]}
            sources.append(src)
        it["MediaSources"] = sources
        if spec.get("SeriesId"):
            it["SeriesName"] = name_for(spec["$series"]); it["SeriesId"] = id_for(spec["SeriesId"])
            it["SeasonId"] = id_for(spec["SeasonId"]) if spec.get("SeasonId") else season_id
            it["SeasonName"] = "Season 1"
        it.update({"CriticRating": 81, "OfficialRating": "PG-13", "ChannelId": spec.get("ChannelId"),
                   "CommunityRating": 7.4})
        for k in ("IndexNumber", "ParentIndexNumber"):
            if k in spec: it[k] = spec[k]
        if "RunTimeTicks" in spec: it["RunTimeTicks"] = spec["RunTimeTicks"]
        it["ProductionYear"] = 2026
        it["IsFolder"] = spec.get("IsFolder", False)
        it["Type"] = spec["Type"]
        it["UserData"] = dict(spec["UserData"], PlayCount=1 if spec["UserData"].get("Played") else 0,
                              LastPlayedDate="2026-11-01T20:00:00.0000000Z", Key=it["Id"], ItemId=it["Id"])
        if "MediaStreams" in spec:
            it["MediaStreams"] = [render_stream(hydrate(s)) for s in spec["MediaStreams"]]
        for k in ("VideoType", "LocationType", "MediaType"):
            if k in spec: it[k] = spec[k]
        it.update(L.images(spec.get("$images", []), backdrop=spec.get("$hasBackdrop", False)))
        return it

    def player_info(spec: dict, name: str) -> dict:
        out = {"MediaSources": []}
        for ms in spec["MediaSources"]:
            src = hydrate({k: v for k, v in ms.items() if k not in ("MediaStreams", "Id")})
            src = {"Protocol": src.pop("Protocol", "File"), "Id": id_for(ms["Id"]),
                   "Path": f"/media/synthetic/{id_for(ms['Id'])}.{ms.get('Container', 'mkv').split(',')[0]}",
                   **src, "ETag": hashlib.sha256(f"etag:{seed}:{ms['Id']}".encode()).hexdigest()[:32],
                   "Name": name,
                   "MediaStreams": [render_stream(hydrate(s)) for s in ms["MediaStreams"]]}
            out["MediaSources"].append(src)
        out["PlaySessionId"] = substitute(spec["PlaySessionId"])
        return out

    remux = specs["player-audio-remux"]
    def case(c: dict, **extra) -> dict:
        item = player_item(c["item"])
        out = {"item": item}
        out.update(extra)
        out["info"] = player_info(c["info"], item["Name"])
        return out

    files["player-audio-remux.json"] = case(specs["player-audio-remux"])
    files["player-direct-regression.json"] = [dict(case(c), audio_index=c["audio_index"])
                                              for c in specs["player-direct-regression"]]
    files["player-playback-info.json"] = [
        dict(case(c, mode=c["mode"], startMs=c["startMs"], request=hydrate(c["request"])), device=DEVICE_ID, server=SERVER_URL)
        for c in specs["player-playback-info"]]

    files["offline/provenance.json"] = {
        "synthetic": True,
        "generator": "tools/fixtures/generate.py",
        "generatorVersion": GENERATOR_VERSION,
        "seed": seed,
        "regenerate": "uv run --no-project python tools/fixtures/generate.py",
        "verify": "uv run --no-project python tools/fixtures/generate.py --check",
        "catalogCount": len(cat_items), "movieCount": 172, "seriesCount": 45, "episodeCount": len(episodes),
        "content": "Invented titles, overviews, people, roles, studios, IDs, image tags and BlurHashes. No real films, "
                   "shows, people or studios are intended; any resemblance is coincidental.",
        "technicalSpecs": "tools/fixtures/specs/*.spec.json hold identity-free stream/codec/flag/URL-parameter structure "
                          "for three playback regressions (see tools/fixtures/README.md).",
        "server": SERVER_URL, "user": USER_ID,
        "art": "Image tags are synthetic. OfflineServer renders deterministic placeholder bitmaps locally; no network."}
    return files


def insert_after(d: dict, after: str, key: str, value) -> dict:
    out = {}
    for k, v in d.items():
        out[k] = v
        if k == after: out[key] = value
    return out


def render_stream(s: dict) -> dict:
    """Add Jellyfin-style DisplayTitle/Title to a technical spec stream (specs carry no free text)."""
    if "DisplayTitle" in s:
        return s
    out = dict(s)
    t = s.get("Type")
    if t == "Video":
        out["DisplayTitle"] = video_display(s.get("Codec", ""), s.get("Width") or 0, s.get("Height") or 0,
                                            s.get("VideoRangeType", "SDR"), s.get("DvProfile"))
    elif t == "Audio":
        out["DisplayTitle"] = audio_display(s.get("Codec", ""), s.get("Language"), s.get("Channels"), s.get("Profile"),
                                            s.get("IsDefault", False), None)
    elif t == "Subtitle":
        out["DisplayTitle"] = sub_display(s.get("Codec", ""), s.get("Language"), s.get("IsDefault", False),
                                          s.get("IsForced", False), s.get("IsExternal", False), None)
    return out


# Intro/credits timings for the 11-episode segment regression (ticks). Technical values only.
SEGMENT_RUNTIMES_MS = [3500960, 3576480, 3630688, 3414080, 3510400, 3488128, 3645952, 3584160, 3142784, 3258496, 3382176]
SEGMENTS = [
    [("Intro", 0, 480480695), ("Outro", 34040271504, 34194161587)],
    [("Intro", 0, 480480000), ("Outro", 35013429478, 35275239765)],
    [("Intro", 0, 480480000), ("Outro", 35609998972, 35875839259)],
    [("Intro", 0, 480480000), ("Outro", 33417912622, 33673642910)],
    [("Intro", 0, 480480000), ("Outro", 34378635827, 34674639259)],
    [("Intro", 0, 480480000), ("Outro", 34174491791, 34474435222)],
    [("Intro", 0, 480480000), ("Outro", 35737871020, 35995964897)],
    [("Intro", 0, 480480000), ("Outro", 35159579743, 35435403620)],
    [("Intro", 0, 480480000)],
    [("Intro", 0, 480480000), ("Outro", 31871979803, 32152116825)],
    [("Intro", 0, 480480000), ("Outro", 33112494996, 33393362018)],
]

COMPACT = {"offline/catalog.json", "offline/home.json", "offline/resume.json", "offline/nextup.json",
           "offline/series-episodes.json", "home-navigation.json", "player-playback-info.json",
           "player-direct-regression.json", "player-audio-remux.json"}


def serialize(name: str, data) -> bytes:
    if name in COMPACT:
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    else:
        text = json.dumps(data, ensure_ascii=False, indent=1)
    return (text + "\n").encode("utf-8")


def write_all(out_dir: str, seed: int) -> dict[str, bytes]:
    files = {name: serialize(name, data) for name, data in build(seed).items()}
    for name, blob in files.items():
        path = os.path.join(out_dir, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(blob)
    return files


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=DEFAULT_OUT, help="resource directory to write (default: app/src/test/resources)")
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--check", action="store_true", help="regenerate in a temp dir and compare with --out")
    args = ap.parse_args()
    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            files = write_all(tmp, args.seed)
            bad = []
            for name, blob in files.items():
                path = os.path.join(args.out, name)
                if not os.path.exists(path) or open(path, "rb").read() != blob:
                    bad.append(name)
            if bad:
                print("fixtures differ from generator output:", *bad, sep="\n  ")
                return 1
            print(f"{len(files)} fixture files match seed {args.seed}")
            return 0
    files = write_all(args.out, args.seed)
    total = sum(len(b) for b in files.values())
    for name in sorted(files):
        print(f"{len(files[name]):>9,}  {name}")
    print(f"{total:>9,}  total ({len(files)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
