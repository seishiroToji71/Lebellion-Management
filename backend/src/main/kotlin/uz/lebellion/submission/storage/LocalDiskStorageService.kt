package uz.lebellion.submission.storage

import org.springframework.stereotype.Service
import uz.lebellion.submission.config.StorageProperties
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Stores photos on local disk behind a random 64-hex key, fanned out two levels (`ab/cd/<key>`) to keep
 * directories small. The key is unpredictable (32 bytes from [SecureRandom]) — the bytes are reachable
 * only via a signed URL, never by guessing. A later S3 impl can replace this behind [StorageService].
 */
@Service
class LocalDiskStorageService(props: StorageProperties) : StorageService {

    private val base: Path = Path.of(props.localDir)
    private val random = SecureRandom()

    override fun store(bytes: ByteArray, contentType: String): String {
        val key = newKey()
        val path = pathFor(key)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return key
    }

    override fun load(storageKey: String): ByteArray {
        require(storageKey.matches(KEY_REGEX)) { "invalid storage key" }
        return Files.readAllBytes(pathFor(storageKey))
    }

    private fun pathFor(key: String): Path = base.resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key)

    private fun newKey(): String {
        val buf = ByteArray(32)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Keys are always 64 lowercase hex chars — also guards the load path against traversal. */
        val KEY_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
