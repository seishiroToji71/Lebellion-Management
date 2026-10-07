package uz.lebellion.submission.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SubmissionProperties::class, StorageProperties::class, MediaProperties::class)
class SubmissionConfig
