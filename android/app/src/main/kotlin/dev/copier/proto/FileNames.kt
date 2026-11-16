package dev.copier.proto

/** File names arrive from the other device, so they are untrusted input. */
object FileNames {
    private const val MAX_BYTES = 200 // ext4/APFS allow 255 UTF-8 bytes; leave room for " (12)".

    /** No path separators, no control/format characters, not hidden, never empty, ≤ 200 UTF-8 bytes. */
    fun sanitize(name: String): String {
        val mapped = StringBuilder()
        name.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val forbidden = cp == '/'.code || cp == '\\'.code || cp == ':'.code ||
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            if (forbidden) mapped.append('_') else mapped.appendCodePoint(cp)
        }
        var result = mapped.toString()
            .trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            .trimStart('.')

        if (result.utf8Size() > MAX_BYTES) {
            val dot = result.lastIndexOf('.')
            val ext = if (dot > 0) result.substring(dot + 1) else ""
            val keepExtension = ext.isNotEmpty() && ext.utf8Size() <= 16
            val suffix = if (keepExtension) ".$ext" else ""
            var base = if (keepExtension) result.substring(0, dot) else result
            while (base.isNotEmpty() && base.utf8Size() + suffix.utf8Size() > MAX_BYTES) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            result = base + suffix
        }
        return result.ifEmpty { "file" }
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}
