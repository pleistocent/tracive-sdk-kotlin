#!/usr/bin/env python3
"""Regenerate BankCodesJson.kt from bank-codes.json.

`bank-codes.json` is the Tracive API's table from a national bank code or a BIC to Tracive's own
bank code, vendored verbatim into all four SDKs. The SDK ships it so `bankAccountByShortCode` and
`bankAccountByBic` can build the record key offline. commonMain has no resource API, so it is
embedded as a Kotlin constant the same way `generate_vectors_kt.py` embeds the grammar fixture, and
with the same round-trip assertion before anything is written.

Usage: python3 tools/generate_bank_codes_kt.py
"""

import pathlib
import sys

from generate_vectors_kt import escape, unescape

ROOT = pathlib.Path(__file__).resolve().parent.parent
JSON_PATH = ROOT / "tracive-sdk/src/commonTest/resources/bank-codes.json"
KT_PATH = ROOT / "tracive-sdk/src/commonMain/kotlin/id/tracive/sdk/query/BankCodesJson.kt"

HEADER = '''// GENERATED FILE — DO NOT EDIT BY HAND.
//
// `tracive-sdk/src/commonTest/resources/bank-codes.json` embedded as a Kotlin constant.
// Regenerate with `python3 tools/generate_bank_codes_kt.py`.
//
// [BANK_CODES_JSON] is BYTE-IDENTICAL to that file, which is itself a verbatim copy of the Tracive
// API's table. `BankCodesVendoredFileTest` compares the two on every JVM test run.
//
// One string literal per line of the file, joined at first use. A single literal would have to be
// folded from hundreds of `+` operands at compile time; a list keeps the initializer to a few
// bytes per line, far below the JVM's 64 KB method limit, and it is built only when the facade
// class is first touched — which is the first bank short-code or BIC lookup, never SDK start-up.

package id.tracive.sdk.query

internal val BANK_CODES_JSON: String =
  listOf(
'''

FOOTER = '''    )
    .joinToString(separator = "\\n", postfix = "\\n")
'''


def main() -> int:
    raw = JSON_PATH.read_bytes()
    text = raw.decode("utf-8")
    if not text.endswith("\n") or text.endswith("\n\n"):
        print("bank-codes.json must end with exactly one trailing newline", file=sys.stderr)
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
