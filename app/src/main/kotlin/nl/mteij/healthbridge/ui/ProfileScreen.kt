package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.BmiClass
import nl.mteij.healthbridge.data.HealthMath
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(vm: AppViewModel, onBack: () -> Unit) {
    val prefs = Graph.prefs
    val version by prefs.version.collectAsState()
    val overview by vm.overview.collectAsState()
    val measured = overview.value?.measurements.orEmpty().filter { it.value != null }.associateBy { it.metric }

    var year by remember { mutableStateOf(if (prefs.birthYear > 0) prefs.birthYear.toString() else "") }
    var height by remember { mutableStateOf(prefs.manualHeightCm.takeIf { it > 0 }?.toInt()?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(prefs.manualWeightKg.takeIf { it > 0 }?.toString().orEmpty()) }

    val age = version.let { HealthMath.age(prefs.birthYear) }
    val weightKg = measured["weight"]?.value ?: prefs.manualWeightKg.toDouble().takeIf { it > 0 }
    val heightM = measured["height"]?.value ?: prefs.manualHeightCm.toDouble().takeIf { it > 0 }?.div(100)
    val maxHr = vm.maxHr()

    LazyColumn(Modifier, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        stickyHeader {
            StickyHeader(true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.profile), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { text ->
                            year = text.filter { it.isDigit() }.take(4)
                            val parsed = year.toIntOrNull()
                            if (year.length == 4 && parsed != null && HealthMath.age(parsed) != null) {
                                prefs.birthYear = parsed
                                vm.profileChanged()
                            }
                        },
                        label = { Text(stringResource(R.string.birth_year)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.sex), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("female" to R.string.sex_female, "male" to R.string.sex_male).forEach { (value, label) ->
                            FilterChip(selected = prefs.sex == value, onClick = { prefs.sex = if (prefs.sex == value) "" else value }, label = { Text(stringResource(label)) })
                        }
                    }
                    if (measured["height"] == null) {
                        OutlinedTextField(
                            value = height,
                            onValueChange = { height = it.filter(Char::isDigit).take(3); prefs.manualHeightCm = height.toFloatOrNull() ?: 0f },
                            label = { Text(stringResource(R.string.height_cm)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (measured["weight"] == null) {
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5); prefs.manualWeightKg = weight.toFloatOrNull() ?: 0f },
                            label = { Text(stringResource(R.string.weight_kg)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (measured["height"] != null || measured["weight"] != null) {
                        Text(stringResource(R.string.from_health_connect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.estimates), style = MaterialTheme.typography.titleMedium)
                    Estimate(
                        stringResource(R.string.est_max_hr), maxHr?.let { "$it bpm" } ?: "–",
                        stringResource(R.string.est_max_hr_note),
                    )
                    Estimate(stringResource(R.string.est_steps), number(HealthMath.stepGoal(age).toDouble()), stringResource(R.string.est_steps_note))
                    Estimate(stringResource(R.string.est_active), "${HealthMath.WEEKLY_ACTIVE_MINUTES} min", stringResource(R.string.est_active_note))
                    Estimate(stringResource(R.string.est_sleep), "${HealthMath.SLEEP_TARGET_MINUTES / 60}+ h", stringResource(R.string.est_sleep_note))
                    if (weightKg != null && heightM != null) {
                        val bmi = HealthMath.bmi(weightKg, heightM)
                        val klass = when (HealthMath.bmiClass(bmi)) {
                            BmiClass.UNDERWEIGHT -> R.string.bmi_under
                            BmiClass.NORMAL -> R.string.bmi_normal
                            BmiClass.OVERWEIGHT -> R.string.bmi_over
                            BmiClass.OBESE -> R.string.bmi_obese
                        }
                        Estimate("BMI", "${number(bmi, 1)} · ${stringResource(klass)}", stringResource(R.string.est_bmi_note))
                        if (age != null) {
                            HealthMath.restingKcal(prefs.sex, age, heightM * 100, weightKg)?.let {
                                Estimate(stringResource(R.string.est_resting_energy), "$it kcal", stringResource(R.string.est_resting_energy_note))
                            }
                        }
                    }
                    Text(stringResource(R.string.estimates_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}

@Composable
private fun Estimate(label: String, value: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
