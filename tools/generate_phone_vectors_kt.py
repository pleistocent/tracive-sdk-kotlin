#!/usr/bin/env python3
"""Regenerates the embedded phone-normalization vectors from the JSON fixture.

Kotlin Multiplatform cannot read a test resource from commonTest, so the vectors are compiled into
`PhoneNormalizationTest.kt`. A compiled copy that drifts from the JSON beside it is exactly the
silent failure the fixture exists to prevent — the tests keep passing against a stale contract. This
generator is deterministic, so CI regenerates and diffs.
"""
import io
import json
import re

FIXTURE = "tracive-sdk/src/commonTest/resources/phone-normalization-vectors.json"
TEST = "tracive-sdk/src/commonTest/kotlin/id/tracive/sdk/query/PhoneNormalizationTest.kt"


def kotlin_string(value: str) -> str:
    out = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return '"' + "".join(c if ord(c) < 0x7F else "\\u%04X" % ord(c) for c in out) + '"'


def replace_block(source: str, header: str, body: str) -> str:
    start = source.index(header) + len(header)
    end = source.index("\n    )\n", start)
    return source[:start] + body + source[end:]


def main() -> None:
    fixture = json.load(io.open(FIXTURE, encoding="utf-8"))
    source = io.open(TEST, encoding="utf-8").read()

    vectors = "\n".join(
        "      PhoneVector(%s, %s, %s),"
        % (kotlin_string(v["country"]), kotlin_string(v["input"]), kotlin_string(v["expected"]))
        for v in fixture["vectors"]
    )
    rejections = "\n".join(
        "      PhoneRejection(%s, %s, %s),"
        % (kotlin_string(r["country"]), kotlin_string(r["input"]), kotlin_string(r["reason"]))
        for r in fixture["rejected"]
    )

    lengths = "\n".join(
        "      %s to setOf(%s)," % (kotlin_string(dial), ", ".join(str(n) for n in values))
        for dial, values in fixture["nationalNumberLengths"].items()
    )

    source = replace_block(source, "  private val vectors =\n    listOf(\n", vectors)
    source = replace_block(source, "  private val nationalNumberLengths =\n    mapOf(\n", lengths)
    source = replace_block(source, "  private val rejections =\n    listOf(\n", rejections)
    source = re.sub(r"assertEquals\(\d+, vectors\.size", "assertEquals(%d, vectors.size" % len(fixture["vectors"]), source)
    source = re.sub(r"assertEquals\(\d+, rejections\.size", "assertEquals(%d, rejections.size" % len(fixture["rejected"]), source)
    source = re.sub(r"assertEquals\(\d+, nationalNumberLengths\.size", "assertEquals(%d, nationalNumberLengths.size" % len(fixture["nationalNumberLengths"]), source)
    source = re.sub(r"assertEquals\(\d+, Country\.entries\.size", "assertEquals(%d, Country.entries.size" % fixture["countryCount"], source)

    io.open(TEST, "w", encoding="utf-8").write(source)
    print("regenerated %d vectors, %d rejections, %d countries, %d national-length entries"
          % (len(fixture["vectors"]), len(fixture["rejected"]), fixture["countryCount"],
             len(fixture["nationalNumberLengths"])))


if __name__ == "__main__":
    main()
