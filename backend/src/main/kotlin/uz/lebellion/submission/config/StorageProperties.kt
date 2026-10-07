package uz.lebellion.submission.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `lebellion.storage.*` — [localDir] is the base directory for the local-disk StorageService. */
@ConfigurationProperties(prefix = "lebellion.storage")
data class StorageProperties(
    val localDir: String = System.getProperty("java.io.tmpdir") + "/lebellion-photos",
)
