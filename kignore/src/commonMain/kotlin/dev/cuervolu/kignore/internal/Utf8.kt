package dev.cuervolu.kignore.internal

/**
 * UTF-8 encoding written out so the bytes are identical on every platform.
 *
 * `String.encodeToByteArray` is not used because the standard library replaces malformed surrogates differently
 * on the JVM and elsewhere. A lone surrogate is encoded here as U+FFFD on every target.
 */
internal object Utf8 {
    private const val REPLACEMENT = 0xFFFD

    /** Code point starting at [index], reading no further than [end]. A lone surrogate gives the replacement. */
    fun codePointAt(
        text: String,
        index: Int,
        end: Int = text.length,
    ): Int {
        val c = text[index]
        if (c.isHighSurrogate() && index + 1 < end && text[index + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        return if (c.isSurrogate()) REPLACEMENT else c.code
    }

    inline fun encode(
        codePoint: Int,
        emit: (Int) -> Unit,
    ) {
        when {
            codePoint < 0x80 -> {
                emit(codePoint)
            }

            codePoint < 0x800 -> {
                emit(0xC0 or (codePoint shr 6))
                emit(0x80 or (codePoint and 0x3F))
            }

            codePoint < 0x10000 -> {
                emit(0xE0 or (codePoint shr 12))
                emit(0x80 or ((codePoint shr 6) and 0x3F))
                emit(0x80 or (codePoint and 0x3F))
            }

            else -> {
                emit(0xF0 or (codePoint shr 18))
                emit(0x80 or ((codePoint shr 12) and 0x3F))
                emit(0x80 or ((codePoint shr 6) and 0x3F))
                emit(0x80 or (codePoint and 0x3F))
            }
        }
    }

    /** Number of bytes [encodeInto] writes for [text]. */
    fun encodedLength(text: String): Int {
        var size = 0
        var i = 0
        while (i < text.length) {
            val codePoint = codePointAt(text, i)
            i += if (codePoint > 0xFFFF) 2 else 1
            size +=
                when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
        }
        return size
    }

    /** Writes the UTF-8 bytes of [text] into [out] starting at [offset] and returns the offset after them. */
    fun encodeInto(
        text: String,
        out: ByteArray,
        offset: Int,
    ): Int {
        var position = offset
        var i = 0
        while (i < text.length) {
            val codePoint = codePointAt(text, i)
            i += if (codePoint > 0xFFFF) 2 else 1
            encode(codePoint) { out[position++] = it.toByte() }
        }
        return position
    }

    fun encode(text: String): ByteArray {
        val bytes = ByteArray(encodedLength(text))
        encodeInto(text, bytes, 0)
        return bytes
    }
}
