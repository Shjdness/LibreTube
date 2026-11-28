package com.github.libretube.util

/** Container signatures, rather than download filenames, determine exported file types. */
data class AudioFileFormat(val extension: String, val mime: String) {
    companion object {
        fun detect(bytes: ByteArray): AudioFileFormat? {
            fun at(offset: Int, text: String) = bytes.size >= offset + text.length &&
                text.indices.all { bytes[offset + it] == text[it].code.toByte() }
            return when {
                at(0, "RIFF") && at(8, "WAVE") -> AudioFileFormat("wav", "audio/wav")
                at(4, "ftyp") -> AudioFileFormat("m4a", "audio/mp4")
                bytes.take(4) == listOf(0x1a, 0x45, 0xdf, 0xa3).map(Int::toByte) -> when {
                    bytes.toString(Charsets.ISO_8859_1).contains("webm") -> AudioFileFormat("webm", "audio/webm")
                    bytes.toString(Charsets.ISO_8859_1).contains("matroska") -> AudioFileFormat("mka", "audio/x-matroska")
                    else -> null
                }
                at(0, "OggS") -> if (bytes.toString(Charsets.ISO_8859_1).contains("OpusHead"))
                    AudioFileFormat("opus", "audio/ogg") else AudioFileFormat("ogg", "audio/ogg")
                at(0, "fLaC") -> AudioFileFormat("flac", "audio/flac")
                at(0, "ID3") -> AudioFileFormat("mp3", "audio/mpeg")
                bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xf6) == 0xf0 -> AudioFileFormat("aac", "audio/aac")
                bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xe0) == 0xe0 -> AudioFileFormat("mp3", "audio/mpeg")
                else -> null
            }
        }

        fun fileName(author: String, title: String, id: String, extension: String): String {
            fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), " ")
                .replace(Regex("\\s+"), " ").trim().trim('.')
            val name = listOf(clean(author), clean(title)).filter(String::isNotBlank).joinToString(" - ").ifBlank { clean(id).ifBlank { "audio" } }
            var end = 0
            var size = 0
            while (end < name.length) {
                val next = end + Character.charCount(name.codePointAt(end))
                val bytes = name.substring(end, next).toByteArray(Charsets.UTF_8).size
                if (size + bytes > 180) break
                size += bytes
                end = next
            }
            val base = name.substring(0, end).trimEnd(' ', '.').ifBlank { clean(id).ifBlank { "audio" } }
            val portable = if (base.uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) "Audio - $base" else base
            return "$portable.$extension"
        }
    }
}
