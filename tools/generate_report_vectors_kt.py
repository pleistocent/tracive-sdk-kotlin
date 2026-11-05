#!/usr/bin/env python3
"""Regenerate ReportPackageVectorsJson.kt from report-package-vectors.json.

The same embedding as generate_vectors_kt.py, for the report package fixture: Kotlin
Multiplatform cannot read a test resource from commonTest, so the vendored file is compiled in as a
constant, and the generator refuses to write unless un-escaping reproduces the file's bytes.

Usage: python3 tools/generate_report_vectors_kt.py
"""

import pathlib
import sys

from generate_vectors_kt import escape, unescape

ROOT = pathlib.Path(__file__).resolve().parent.parent
JSON_PATH = ROOT / "tracive-sdk/src/commonTest/resources/report-package-vectors.json"
KT_PATH = ROOT / "tracive-sdk/src/commonTest/kotlin/id/tracive/sdk/report/ReportPackageVectorsJson.kt"

HEADER = '''// GENERATED FILE — DO NOT EDIT BY HAND.
//
// `tracive-sdk/src/commonTest/resources/report-package-vectors.json` embedded as a Kotlin constant,
// byte for byte. Regenerate with `python3 tools/generate_report_vectors_kt.py`.

package id.tracive.sdk.report

internal val REPORT_PACKAGE_VECTORS_JSON: String =
  listOf(
'''

FOOTER = '''    )
    .joinToString(separator = "\\n", postfix = "\\n")
'''


def main() -> int:
    raw = JSON_PATH.read_bytes()
    text = raw.decode("utf-8")
    if not text.endswith("\n") or text.endswith("\n\n"):
        print("fixture must end with exactly one trailing newline", file=sys.stderr)
        return 1
    literals = [escape(line) for line in text[:-1].split("\n")]

    round_tripped = "\n".join(unescape(literal) for literal in literals) + "\n"
    if round_tripped.encode("utf-8") != raw:
        print("round trip mismatch — refusing to write", file=sys.stderr)
        return 1

    body = "".join('      "%s",\n' % literal for literal in literals)
    KT_PATH.parent.mkdir(parents=True, exist_ok=True)
    KT_PATH.write_text(HEADER + body + FOOTER, encoding="utf-8")
    print("wrote %s (%d lines)" % (KT_PATH.relative_to(ROOT), len(literals)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
