package id.tracive.sdk.extended.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableIdentifierTest {

  @Test
  fun eachKindOfKeyReadsLikeWhatItNames() {
    val cases =
      mapOf(
        "PHONE_+6281234567891" to ReadableIdentifier(MediaKind.PHONE, "+6281234567891"),
        "EMAIL_budi@mail.com" to ReadableIdentifier(MediaKind.EMAIL, "budi@mail.com"),
        "BANK_IDN_BCA_1234567890" to ReadableIdentifier(MediaKind.BANK, "BCA 1234567890"),
        "BANK_IDN_BCA_12_34" to ReadableIdentifier(MediaKind.BANK, "BCA 12_34"),
        "BANK_IBAN_GB29NWBK60161331926819" to
          ReadableIdentifier(MediaKind.BANK, "GB29NWBK60161331926819"),
        "CRYPTO_ETH_0xabc" to ReadableIdentifier(MediaKind.CRYPTO, "ETH 0xabc"),
        "USERNAME_INSTAGRAM_scammer_x" to
          ReadableIdentifier(MediaKind.SOCIAL_MEDIA, "Instagram @scammer_x"),
        "USERNAME_TIKTOK_@budi" to ReadableIdentifier(MediaKind.SOCIAL_MEDIA, "Tiktok @budi"),
      )

    cases.forEach { (key, expected) -> assertEquals(expected, readableIdentifier(key), key) }
  }

  @Test
  fun aKeyThatDoesNotParseIsShownAsItIs() {
    listOf("BANK_IDN", "CRYPTO_ETH", "USERNAME_X", "PHONE", "SOMETHING_ELSE", "").forEach {
      assertEquals(ReadableIdentifier(MediaKind.OTHER, it), readableIdentifier(it))
    }
  }
}
