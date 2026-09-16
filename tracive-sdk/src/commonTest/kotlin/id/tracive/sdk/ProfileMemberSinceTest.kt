package id.tracive.sdk

import id.tracive.sdk.client.SdkJson
import id.tracive.sdk.model.ProfileInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant

/**
 * `memberSince` as `GET /v1/profile` really sends it: a date, not an instant.
 *
 * The server derives it from the account's creation timestamp and truncates to ten characters, and
 * its spec types the field `format: date`. Every real user therefore has one, which made this the
 * rare decode failure that affects everyone rather than an edge case.
 */
class ProfileMemberSinceTest {

  @Test
  fun aDateOnlyMemberSinceReadsAsMidnightUtc() {
    val payload =
      """
      { "sub": "ppid_b64url", "name": "Nico", "country": "IDN", "tier": "plus",
        "memberSince": "2025-03-14" }
      """
        .trimIndent()

    val profile = SdkJson.decodeFromString(ProfileInfo.serializer(), payload)

    assertEquals("ppid_b64url", profile.sub)
    assertEquals(Instant.parse("2025-03-14T00:00:00Z"), profile.memberSince)
  }

  /** A full instant is still read as itself — the tolerance only adds a shape. */
  @Test
  fun aFullInstantStillDecodes() {
    val profile =
      SdkJson.decodeFromString(
        ProfileInfo.serializer(),
        """{"sub":"ppid","memberSince":"2023-04-05T06:07:08Z"}""",
      )

    assertEquals(Instant.parse("2023-04-05T06:07:08Z"), profile.memberSince)
  }

  /** Not granted is still null, and null is still "not shared" rather than "not set". */
  @Test
  fun anAbsentMemberSinceStaysNull() {
    val profile = SdkJson.decodeFromString(ProfileInfo.serializer(), """{"sub":"ppid"}""")

    assertNull(profile.memberSince)
    assertNull(profile.name)
  }

  /** The server sends an explicit null for an account with no creation timestamp. */
  @Test
  fun anExplicitNullMemberSinceStaysNull() {
    val profile =
      SdkJson.decodeFromString(
        ProfileInfo.serializer(),
        """{"sub":"ppid","name":null,"country":null,"tier":"free","memberSince":null}""",
      )

    assertNull(profile.memberSince)
    assertEquals("free", profile.tier)
  }
}
