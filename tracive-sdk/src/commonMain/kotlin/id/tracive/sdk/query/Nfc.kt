package id.tracive.sdk.query

/**
 * Unicode Normalization Form C, in pure Kotlin.
 *
 * The backend folds free-text identifiers with `String.prototype.normalize("NFC")` before it builds
 * a stored record key, so the SDK has to produce the same code points or it builds a key that can
 * never hit — and a miss is still billed. `java.text.Normalizer` is JVM-only and there is no common
 * Kotlin equivalent, so the algorithm from UAX #15 is implemented here against the generated tables
 * in [UnicodeCanonicalData]. Keeping it in `commonMain` means Android, iOS and any future target
 * fold identically; an `expect`/`actual` split would let two platforms disagree silently.
 *
 * Steps, in order: canonical decomposition (table plus the algorithmic Hangul rule), canonical
 * ordering of combining marks by combining class, then canonical composition.
 *
 * Coverage is the Basic Multilingual Plane; see [UnicodeCanonicalData] for exactly what that leaves
 * out and why it does not matter for the identifiers this SDK folds. Supplementary-plane characters
 * (surrogate pairs) survive untouched and in order.
 */
internal object Nfc {

  private const val S_BASE = 0xAC00
  private const val L_BASE = 0x1100
  private const val V_BASE = 0x1161
  private const val T_BASE = 0x11A7
  private const val L_COUNT = 19
  private const val V_COUNT = 21
  private const val T_COUNT = 28
  private const val N_COUNT = V_COUNT * T_COUNT
  private const val S_COUNT = L_COUNT * N_COUNT

  private const val NO_COMPOSITION = -1

  /**
   * The lowest code point that can take part in canonical normalisation at all: U+00C0 is the first
   * character with a canonical decomposition, and the first combining mark is far above it. A
   * string made only of code points below this is already in NFC, which is the overwhelmingly
   * common case (every ASCII email address, handle, bank code and chain code).
   */
  private const val FIRST_INTERESTING_CODE_POINT = 0xC0

  /** @return [value] in Normalization Form C. */
  fun normalize(value: String): String {
    if (isTriviallyNormalized(value)) return value
    val decomposed = CodeBuffer(value.length + 8)
    for (index in value.indices) decompose(value[index], decomposed)
    canonicalOrder(decomposed)
    return compose(decomposed)
  }

  private fun isTriviallyNormalized(value: String): Boolean {
    for (index in value.indices) {
      if (value[index].code >= FIRST_INTERESTING_CODE_POINT) return false
    }
    return true
  }

  /** Append the full canonical decomposition of [value] to [out]. */
  private fun decompose(value: Char, out: CodeBuffer) {
    val code = value.code

    val syllableIndex = code - S_BASE
    if (syllableIndex in 0 until S_COUNT) {
      out.add((L_BASE + syllableIndex / N_COUNT).toChar())
      out.add((V_BASE + (syllableIndex % N_COUNT) / T_COUNT).toChar())
      val trailing = T_BASE + syllableIndex % T_COUNT
      if (trailing != T_BASE) out.add(trailing.toChar())
      return
    }

    val entry = indexOf(UnicodeCanonicalData.DECOMPOSITION_KEYS, value)
    if (entry < 0) {
      out.add(value)
      return
    }
    // Decompositions nest (U+1E69 -> U+1E63 + U+0307 -> U+0073 + U+0323 + U+0307), so recurse.
    decompose(UnicodeCanonicalData.DECOMPOSITION_FIRST[entry], out)
    val second = UnicodeCanonicalData.DECOMPOSITION_SECOND[entry]
    // U+0000 is the table's singleton-decomposition marker; it is never a real component.
    if (second != '\u0000') decompose(second, out)
  }

  /**
   * Sort runs of combining marks into canonical order: repeatedly swap an adjacent pair whose
   * classes are out of order. A starter has class 0, so the `classA <= classB` guard also stops a
   * mark from being dragged past the base character it belongs to.
   */
  private fun canonicalOrder(buffer: CodeBuffer) {
    for (i in 1 until buffer.size) {
      val classB = combiningClass(buffer.data[i])
      if (classB == 0) continue
      var j = i
      while (j > 0) {
        val classA = combiningClass(buffer.data[j - 1])
        if (classA <= classB) break
        buffer.swap(j - 1, j)
        j -= 1
      }
    }
  }

  /** The composition pass from UAX #15, tracking the current starter and the blocking class. */
  private fun compose(buffer: CodeBuffer): String {
    if (buffer.size == 0) return ""
    val out = CodeBuffer(buffer.size)
    var starterIndex = 0
    var starter = buffer.data[0]
    out.add(starter)
    // A string that begins with a combining mark is not well formed; 256 is above every real
    // combining class, so nothing composes onto it.
    var lastClass = combiningClass(starter)
    if (lastClass != 0) lastClass = 256

    for (i in 1 until buffer.size) {
      val current = buffer.data[i]
      val currentClass = combiningClass(current)
      val composite = composePair(starter, current)
      if (composite != NO_COMPOSITION && (lastClass < currentClass || lastClass == 0)) {
        starter = composite.toChar()
        out.data[starterIndex] = starter
      } else {
        if (currentClass == 0) {
          starterIndex = out.size
          starter = current
        }
        lastClass = currentClass
        out.add(current)
      }
    }
    return out.data.concatToString(0, out.size)
  }

  /** @return the composition of [first] and [second], or [NO_COMPOSITION] if there is none. */
  private fun composePair(first: Char, second: Char): Int {
    val leadingIndex = first.code - L_BASE
    if (leadingIndex in 0 until L_COUNT) {
      val vowelIndex = second.code - V_BASE
      if (vowelIndex in 0 until V_COUNT) {
        return S_BASE + (leadingIndex * V_COUNT + vowelIndex) * T_COUNT
      }
    }
    val syllableIndex = first.code - S_BASE
    if (syllableIndex in 0 until S_COUNT && syllableIndex % T_COUNT == 0) {
      val trailingIndex = second.code - T_BASE
      if (trailingIndex in 1 until T_COUNT) return first.code + trailingIndex
    }

    var low = 0
    var high = UnicodeCanonicalData.COMPOSITION_FIRST.length - 1
    while (low <= high) {
      val mid = (low + high) ushr 1
      val a = UnicodeCanonicalData.COMPOSITION_FIRST[mid]
      val b = UnicodeCanonicalData.COMPOSITION_SECOND[mid]
      when {
        a < first || (a == first && b < second) -> low = mid + 1
        a > first || (a == first && b > second) -> high = mid - 1
        else -> return UnicodeCanonicalData.COMPOSITION_RESULT[mid].code
      }
    }
    return NO_COMPOSITION
  }

  private fun combiningClass(value: Char): Int {
    val index = indexOf(UnicodeCanonicalData.COMBINING_CLASS_KEYS, value)
    return if (index < 0) 0 else UnicodeCanonicalData.COMBINING_CLASS_VALUES[index].code
  }

  /** Binary search over an ascending table of code points held in a [String]. */
  private fun indexOf(keys: String, target: Char): Int {
    var low = 0
    var high = keys.length - 1
    while (low <= high) {
      val mid = (low + high) ushr 1
      val candidate = keys[mid]
      when {
        candidate < target -> low = mid + 1
        candidate > target -> high = mid - 1
        else -> return mid
      }
    }
    return -1
  }
}

/**
 * A growable `CharArray`, so the normaliser can reorder and rewrite code points in place without
 * relying on any `StringBuilder` mutator that is not part of the common stdlib.
 */
private class CodeBuffer(initialCapacity: Int) {
  var data: CharArray = CharArray(if (initialCapacity < 8) 8 else initialCapacity)
    private set

  var size: Int = 0
    private set

  fun add(value: Char) {
    if (size == data.size) data = data.copyOf(data.size * 2)
    data[size] = value
    size += 1
  }

  fun swap(first: Int, second: Int) {
    val held = data[first]
    data[first] = data[second]
    data[second] = held
  }
}
