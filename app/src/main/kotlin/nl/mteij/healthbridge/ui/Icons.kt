package nl.mteij.healthbridge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsMartialArts
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.ui.graphics.vector.ImageVector

/** One clean icon per kind of workout, replacing the emoji. */
fun workoutIcon(type: String): ImageVector = when {
    type.startsWith("running") -> Icons.Rounded.DirectionsRun
    type.startsWith("walking") -> Icons.Rounded.DirectionsWalk
    type == "hiking" -> Icons.Rounded.Hiking
    type.startsWith("biking") -> Icons.Rounded.DirectionsBike
    type.startsWith("swimming") -> Icons.Rounded.Pool
    type.startsWith("rowing") -> Icons.Rounded.Rowing
    type == "strength_training" || type == "weightlifting" || type == "calisthenics" || type == "boot_camp" -> Icons.Rounded.FitnessCenter
    type == "yoga" || type == "pilates" || type == "stretching" || type == "guided_breathing" -> Icons.Rounded.SelfImprovement
    type == "soccer" || type == "football_american" || type == "football_australian" -> Icons.Rounded.SportsSoccer
    type == "tennis" || type == "table_tennis" || type == "squash" || type == "badminton" || type == "racquetball" -> Icons.Rounded.SportsTennis
    type == "martial_arts" || type == "boxing" -> Icons.Rounded.SportsMartialArts
    type == "hiit" -> Icons.Rounded.Bolt
    else -> Icons.Rounded.LocalFireDepartment
}

val SleepIcon: ImageVector get() = Icons.Rounded.Bedtime
