package uz.lebellion.support

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** Minimal `multipart/form-data` body builder for the integration tests (Java HttpClient has none). */
object MultipartBody {

    sealed interface Part
    data class Field(val name: String, val value: String) : Part
    data class FilePart(val name: String, val filename: String, val contentType: String, val bytes: ByteArray) : Part

    data class Built(val contentType: String, val body: ByteArray)

    fun build(parts: List<Part>): Built {
        val boundary = "----lebellion${UUID.randomUUID()}"
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(UTF_8))
        for (part in parts) {
            w("--$boundary\r\n")
            when (part) {
                is Field -> {
                    w("Content-Disposition: form-data; name=\"${part.name}\"\r\n\r\n")
                    w(part.value)
                    w("\r\n")
                }
                is FilePart -> {
                    w("Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.filename}\"\r\n")
                    w("Content-Type: ${part.contentType}\r\n\r\n")
                    out.write(part.bytes)
                    w("\r\n")
                }
            }
        }
        w("--$boundary--\r\n")
        return Built("multipart/form-data; boundary=$boundary", out.toByteArray())
    }
}
