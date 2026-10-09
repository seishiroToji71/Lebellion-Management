package uz.lebellion.kpi.web

import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.kpi.domain.BandLabel
import java.util.UUID

data class ScoreSheetItemView(
    val id: UUID,
    val criterionCode: String,
    val type: ItemType,
    val titleRu: String,
    val titleUz: String,
    val points: Int,
    val direction: String?,
    val sortOrder: Int,
)

data class ScoreBandView(
    val fromScore: Int,
    val toScore: Int,
    val label: BandLabel,
    val percent: Int?,
)

data class ScoreSheetResponse(
    val id: UUID,
    val roleKey: String,
    val nameRu: String,
    val nameUz: String,
    val positionSuggestion: String?,
    val active: Boolean,
    val totalPoints: Int,
    val items: List<ScoreSheetItemView>,
    val bands: List<ScoreBandView>,
)

data class ScoreSheetSummary(
    val id: UUID,
    val roleKey: String,
    val nameRu: String,
    val nameUz: String,
    val active: Boolean,
)
