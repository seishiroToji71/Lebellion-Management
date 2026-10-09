package uz.lebellion.kpi.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(KpiProperties::class)
class KpiConfig
