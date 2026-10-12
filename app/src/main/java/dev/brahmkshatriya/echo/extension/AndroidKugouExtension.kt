package dev.brahmkshatriya.echo.extension

import android.util.Base64

class AndroidKugouExtension : KugouExtension() {
    override fun decodeBase64(content: String): ByteArray {
        return Base64.decode(content.trim(), Base64.DEFAULT)
    }
}
