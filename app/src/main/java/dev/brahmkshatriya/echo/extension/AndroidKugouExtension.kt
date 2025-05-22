package dev.brahmkshatriya.echo.extension

import android.util.Base64
import java.nio.charset.StandardCharsets

class AndroidKugouExtension: KugouExtension() {
    override fun decodeBASE64(content: String): String {
        return Base64.decode(content, Base64.DEFAULT).toString(StandardCharsets.UTF_8)
    }
}