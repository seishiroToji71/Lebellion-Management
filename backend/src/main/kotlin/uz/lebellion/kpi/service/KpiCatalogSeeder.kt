package uz.lebellion.kpi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.kpi.config.KpiProperties
import uz.lebellion.kpi.domain.BandLabel
import uz.lebellion.kpi.domain.KpiCriterion
import uz.lebellion.kpi.domain.ScoreBand
import uz.lebellion.kpi.domain.ScoreSheet
import uz.lebellion.kpi.domain.ScoreSheetItem
import uz.lebellion.kpi.repo.KpiCriterionRepository
import uz.lebellion.kpi.repo.ScoreBandRepository
import uz.lebellion.kpi.repo.ScoreSheetItemRepository
import uz.lebellion.kpi.repo.ScoreSheetRepository
import uz.lebellion.kpi.web.KpiSeedResult
import java.io.File
import java.util.UUID

/**
 * Seeds KPI criteria, sheets, items and bands from the local catalog/bands JSON (paths from config). NOT
 * an xlsx importer — it reads the curated kpi_catalog.json (+ separate kpi_bands.json). Idempotent: criteria
 * upsert by `code`, sheets by `role_key`, and each sheet's items+bands are rebuilt. FOUNDER-only; when the
 * catalog later gains ofitsiant, re-running simply adds it. Sum=100 and band coverage are validated per sheet.
 */
@Service
class KpiCatalogSeeder(
    private val mapper: ObjectMapper,
    private val props: KpiProperties,
    private val criteria: KpiCriterionRepository,
    private val sheets: ScoreSheetRepository,
    private val items: ScoreSheetItemRepository,
    private val bands: ScoreBandRepository,
) {
    /** Catalog role (RU) -> stable role_key. Roles absent here (e.g. a future ofitsiant sheet) are skipped. */
    private val roleKeys = mapOf(
        "Шеф повар" to "BOSH_OSHPAZ",
        "Повар мангалщик" to "MANGALCHI",
        "Повар донарщик" to "DONARCHI",
        "Повар мучного цеха" to "TANDIRCHI",
        "Повар холодного цеха" to "SALATCHI",
        "Повар горячего цеха" to "ISSIQ_OVQATCHI",
        "Снабженец" to "SNABJENETS",
        "Хостес" to "HOSTES",
        "Менеджер" to "MENEJER",
        "Управляющий" to "MUDIR",
        "Официант" to "ofitsiant",
    )

    /** Not in the position list (owner): seeded but inactive, no position. */
    private val inactiveRoleKeys = setOf("SNABJENETS", "HOSTES")

    @Transactional
    fun seed(principal: AuthPrincipal): KpiSeedResult {
        if (principal.role != Role.FOUNDER) throw ForbiddenException("only a founder may seed the KPI catalog")
        val catalog = read(props.catalogPath, "lebellion.kpi.catalog-path")
        val bandsDoc = read(props.bandsPath, "lebellion.kpi.bands-path")
        val org = principal.organizationId

        @Suppress("UNCHECKED_CAST")
        val criteriaJson = catalog["criteria"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val sheetsJson = catalog["score_sheets"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val bandsByRole = bandsDoc["bands"] as Map<String, List<Map<String, Any?>>>

        // 1) criteria — upsert by code
        val idByCode = HashMap<String, UUID>()
        for (c in criteriaJson) {
            val code = c["id"] as String
            val entity = criteria.findByOrganizationIdAndCode(org, code)
                ?: KpiCriterion(org, code, ItemType.MANUAL, code, code)
            entity.type = ItemType.valueOf(c["type"] as String)
            entity.titleRu = (c["short_ru"] as String?)?.take(255) ?: code
            entity.titleUz = (c["short_uz"] as String?)?.take(255) ?: (c["short_ru"] as String? ?: code)
            entity.originalText = c["original_text"] as String?
            idByCode[code] = criteria.save(entity).id!!
        }

        // 2) sheets + items + bands — upsert sheet by role_key, rebuild children
        var sheetCount = 0
        for (s in sheetsJson) {
            val role = s["role"] as String
            val roleKey = roleKeys[role] ?: continue
            val positionSuggestion = s["position_suggestion"] as String?
            val cleaned = positionSuggestion?.replace("(предположение)", "")?.trim()
            val nameUz = if (cleaned.isNullOrBlank() || cleaned.startsWith("—")) role else cleaned

            val sheet = (sheets.findByOrganizationIdAndRoleKey(org, roleKey) ?: ScoreSheet(org, roleKey, role, nameUz)).apply {
                nameRu = role
                this.nameUz = nameUz
                this.positionSuggestion = positionSuggestion
                active = roleKey !in inactiveRoleKeys
            }
            val saved = sheets.save(sheet)

            @Suppress("UNCHECKED_CAST")
            val itemsJson = s["items"] as List<Map<String, Any?>>
            ScoreSheetValidation.requireSumIs100(itemsJson.map { (it["points"] as Number).toInt() })
            val bandDefs = bandsByRole[roleKey] ?: throw RequestValidationException("no bands for role_key $roleKey")
            ScoreSheetValidation.requireBandsCover0To100(bandDefs.map { (it["from"] as Number).toInt() to (it["to"] as Number).toInt() })

            // rebuild children (bulk deletes execute immediately, before the re-inserts)
            items.deleteByScoreSheetId(saved.id!!)
            bands.deleteByScoreSheetId(saved.id!!)
            itemsJson.forEachIndexed { idx, it ->
                val code = it["criterion_id"] as String
                val critId = idByCode[code] ?: throw RequestValidationException("sheet $roleKey references unknown criterion $code")
                items.save(ScoreSheetItem(org, saved.id!!, critId, (it["points"] as Number).toInt(), it["direction"] as String?, idx))
            }
            bandDefs.forEachIndexed { idx, b ->
                bands.save(
                    ScoreBand(
                        org, saved.id!!,
                        (b["from"] as Number).toInt(), (b["to"] as Number).toInt(),
                        BandLabel.valueOf(b["label"] as String), (b["percent"] as Number?)?.toInt(), idx,
                    ),
                )
            }
            sheetCount++
        }
        return KpiSeedResult(criteria = idByCode.size, sheets = sheetCount)
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(path: String?, prop: String): Map<String, Any?> {
        if (path.isNullOrBlank()) throw RequestValidationException("$prop is not configured")
        val file = File(path)
        if (!file.isFile) throw RequestValidationException("$prop does not point to a file: $path")
        return mapper.readValue(file.readBytes(), Map::class.java) as Map<String, Any?>
    }
}
