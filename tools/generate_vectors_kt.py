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
        if character == "\\":
            out.append("\\\\")
        elif character == '"':
            out.append('\\"')
        elif character == "$":
            out.append("\\$")
        elif ord(character) < 0x20 or ord(character) > 0x7E:
            out.append("\\u%04x" % ord(character))
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
            out.append(chr(int(literal[index + 2 : index + 6], 16)))
            index += 6
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
