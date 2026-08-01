#!/usr/bin/env python3
"""Regenerate QueryGrammarVectorsJson.kt from query-grammar-vectors.json.

Kotlin Multiplatform has no common API for reading a test resource from commonTest, so the
vendored fixture is embedded as a Kotlin constant. This generator produces that constant and then
asserts that un-escaping it reproduces the fixture's bytes exactly, so the two can never drift.

Usage: python3 tools/generate_vectors_kt.py
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
JSON_PATH = ROOT / "tracive-sdk/src/commonTest/resources/query-grammar-vectors.json"
KT_PATH = ROOT / "tracive-sdk/src/commonTest/kotlin/id/tracive/sdk/QueryGrammarVectorsJson.kt"

HEADER = '''// GENERATED FILE — DO NOT EDIT BY HAND.
//
// `tracive-sdk/src/commonTest/resources/query-grammar-vectors.json` embedded as a Kotlin constant.
// Regenerate with `python3 tools/generate_vectors_kt.py`.
//
// The fixture is the cross-SDK anti-drift contract: Kotlin,
// Flutter, Swift and the backend must all fold these inputs identically. It lives in this repo as a
// test *resource*, but Kotlin Multiplatform has no common API for reading a resource from
// commonTest, so it is embedded here instead.
//
// [QUERY_GRAMMAR_VECTORS_JSON] is BYTE-IDENTICAL to that file: it was generated from it by escaping
// only `\\`, `"`, `$` and non-ASCII code points, joining the lines with "\\n" and appending the
// file's single trailing newline. The generator asserted that un-escaping reproduces the file's
// bytes exactly before writing this file. When the fixture changes, regenerate — do not hand-edit.
//
// Byte-identity matters for several vectors in particular. `email-nfc-decomposed` carries "cafe"
// with a COMBINING ACUTE ACCENT (U+0065 U+0301) as input and a precomposed U+00E9 as the
// expectation; `email-nbsp-is-trimmed` carries U+00A0, `email-zwsp-is-not-trimmed` carries U+200B,
// and `bank-account-nbsp-stripped` carries U+00A0 inside an account number. An editor that
// "helpfully" normalised or re-spaced this file would make those vectors vacuous, which is why
// every non-ASCII code point is written as an escape rather than as a literal character.

package id.tracive.sdk

internal val QUERY_GRAMMAR_VECTORS_JSON: String =
  listOf(
'''

FOOTER = '''    )
    .joinToString(separator = "\\n", postfix = "\\n")
'''


def escape(line: str) -> str:
    out = []
    for character in line:
        code = ord(character)
        if character == "\\":
            out.append("\\\\")
        elif character == '"':
            out.append('\\"')
        elif character == "$":
            out.append("\\$")
        elif code > 0xFFFF:
            # Kotlin's \u escape is exactly four hex digits: one UTF-16 code unit, not one code
            # point. "\\u%04x" on a supplementary code point emits five digits, which Kotlin reads
            # as a four-digit escape followed by a literal hex digit — a different string, silently.
            # Emit the surrogate pair instead, which is what the code point is in UTF-16 anyway.
            shifted = code - 0x10000
            out.append("\\u%04x" % (0xD800 + (shifted >> 10)))
            out.append("\\u%04x" % (0xDC00 + (shifted & 0x3FF)))
        elif code < 0x20 or code > 0x7E:
            out.append("\\u%04x" % code)
        else:
            out.append(character)
    return "".join(out)


def unescape(literal: str) -> str:
    """Mirror of escape(), used only to prove the round trip."""
    out = []
    index = 0
    while index < len(literal):
        character = literal[index]
        if character != "\\":
            out.append(character)
            index += 1
            continue
        following = literal[index + 1]
        if following == "u":
            code = int(literal[index + 2 : index + 6], 16)
            index += 6
            # Recombine a pair written by escape(). Python strings hold whole code points, and a
            # lone surrogate has no UTF-8 encoding at all — so without this the round-trip check
            # below would raise instead of comparing, which is not the same as passing.
            if 0xD800 <= code <= 0xDBFF and literal[index : index + 2] == "\\u":
                low = int(literal[index + 2 : index + 6], 16)
                if 0xDC00 <= low <= 0xDFFF:
                    code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                    index += 6
            out.append(chr(code))
        else:
            out.append(following)
            index += 2
    return "".join(out)


def main() -> int:
    raw = JSON_PATH.read_bytes()
    text = raw.decode("utf-8")
    if not text.endswith("\n") or text.endswith("\n\n"):
        print("fixture must end with exactly one trailing newline", file=sys.stderr)
        return 1
    lines = text[:-1].split("\n")

    literals = [escape(line) for line in lines]

    round_tripped = "\n".join(unescape(literal) for literal in literals) + "\n"
    if round_tripped.encode("utf-8") != raw:
        print("round trip mismatch — refusing to write", file=sys.stderr)
        return 1

    body = "".join('      "%s",\n' % literal for literal in literals)
    KT_PATH.write_text(HEADER + body + FOOTER, encoding="utf-8")
    print("wrote %s (%d lines)" % (KT_PATH.relative_to(ROOT), len(lines)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
