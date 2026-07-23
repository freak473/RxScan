package com.rxscan.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.data.FlagKind
import com.rxscan.app.data.Medication
import com.rxscan.app.share.DoctorShare
import com.rxscan.app.ui.components.InkChip
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.Amber
import com.rxscan.app.ui.theme.AmberBg
import com.rxscan.app.ui.theme.AmberLine
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Verify (design: scr-verify). The hard gate: nothing is scheduled until every
 * medicine is confirmed. Flags never suggest a value — the user types what the
 * paper says, or shares the question with their doctor (device-to-doctor).
 *
 * Editing model (PRD §6 "editable cards"):
 *  - EVERY field is user-editable on every unconfirmed card (pencil → dialog,
 *    prefilled with the currently displayed reading — the user corrects it),
 *    because even a high-confidence reading can be wrong.
 *  - A flagged field keeps its re-check box visible until the card is confirmed;
 *    flagged fields are owned by the flag box, not the dialog.
 *  - "Message your doctor" is on every card (not just flagged ones) and attaches
 *    the prescription photo from local storage (device-to-doctor).
 *  - The flag input is seeded only with the USER's own previous entry, never a
 *    system value (CDSCO: flag, don't correct).
 */
@Composable
fun VerifyScreen(meds: List<Medication>, onAllConfirmed: (List<Medication>) -> Unit) {
    var confirmed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var resolved by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var nameEdits by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var strengthEdits by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var scheduleEdits by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var foodEdits by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var durationEdits by rememberSaveable { mutableStateOf(mapOf<String, String>()) }

    val allConfirmed = confirmed.size == meds.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        // Head with progress
        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp)) {
            Text("Check each medicine", fontFamily = DisplayFamily, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Compare with your paper. Nothing is scheduled until you confirm all four.",
                fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(GreenSoft),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (meds.isEmpty()) 0f else confirmed.size / meds.size.toFloat())
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Green),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${confirmed.size} of ${meds.size} confirmed",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            meds.forEach { med ->
                val displayName = nameEdits[med.id] ?: med.name
                val displayStrength = displayStrength(med, resolved[med.id], strengthEdits[med.id])

                if (med.id in confirmed) {
                    ConfirmedBar(
                        name = displayName,
                        strength = displayStrength,
                        onEdit = { confirmed = confirmed - med.id },
                    )
                } else {
                    MedCard(
                        med = med,
                        displayName = displayName,
                        displayStrength = displayStrength,
                        displaySchedule = scheduleEdits[med.id] ?: med.schedule,
                        displayFood = foodEdits[med.id] ?: med.food,
                        displayDurationText = displayDuration(med, resolved[med.id], durationEdits[med.id]),
                        resolvedValue = resolved[med.id],
                        onResolve = { resolved = resolved + (med.id to it) },
                        onMetaSave = { edit ->
                            nameEdits = nameEdits + (med.id to edit.name)
                            if (edit.strength != null) strengthEdits = strengthEdits + (med.id to edit.strength)
                            scheduleEdits = scheduleEdits + (med.id to edit.schedule)
                            foodEdits = foodEdits + (med.id to edit.food)
                            if (edit.duration != null) durationEdits = durationEdits + (med.id to edit.duration)
                        },
                        onConfirm = { confirmed = confirmed + med.id },
                    )
                }
            }

            // Add a medicine we missed (partial-extraction path)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.5.dp, PaperLine), RoundedCornerShape(16.dp))
                    .clickable { }
                    .padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a medicine we missed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Muted)
            }
            Spacer(Modifier.height(4.dp))
        }

        // Foot: scribe note + gated action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(1.dp, PaperLine)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Text(
                "Confirm against your paper. When in doubt, ask your doctor or pharmacist.",
                fontSize = 12.sp, lineHeight = 17.sp, color = Muted,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            PrimaryButton(
                text = if (allConfirmed) "Continue — set meal times" else "Confirm each medicine to continue",
                onClick = {
                    val finalMeds = meds.map { med ->
                        med.copy(
                            name = nameEdits[med.id] ?: med.name,
                            strength = displayStrength(med, resolved[med.id], strengthEdits[med.id]),
                            schedule = scheduleEdits[med.id] ?: med.schedule,
                            food = foodEdits[med.id] ?: med.food,
                            duration = displayDuration(med, resolved[med.id], durationEdits[med.id]),
                        )
                    }
                    onAllConfirmed(finalMeds)
                },
                enabled = allConfirmed,
            )
        }
    }
}

/** Collapsed confirmed card: green bar with the confirmed reading + Edit. */
@Composable
private fun ConfirmedBar(name: String, strength: String?, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GreenTint)
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Confirmed", tint = Green, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            name + (strength?.let { " · $it" } ?: ""),
            fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Edit",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green,
            modifier = Modifier.clickable(onClick = onEdit).padding(6.dp),
        )
    }
}

/** One dialog edit: every field the user corrected. Flag-owned fields come back null. */
private data class MedEdit(
    val name: String,
    val strength: String?,
    val schedule: String,
    val food: String,
    val duration: String?,
)

@Composable
private fun MedCard(
    med: Medication,
    displayName: String,
    displayStrength: String?,
    displaySchedule: String,
    displayFood: String,
    displayDurationText: String?,
    resolvedValue: String?,
    onResolve: (String) -> Unit,
    onMetaSave: (MedEdit) -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val needsResolve = med.flag != null && resolvedValue == null
    val durationText = displayDurationText
    var editing by remember { mutableStateOf(false) }

    // A field governed by a re-check flag is owned by the flag box, not the dialog.
    val strengthEditableHere = med.flag?.kind != FlagKind.STRENGTH
    val durationEditableHere = med.flag?.kind != FlagKind.DURATION

    PaperCard {
        Column(modifier = Modifier.padding(16.dp)) {

            // Head: name + strength (+ edit pencil) / tag
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit name or strength",
                            tint = Faint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editing = true }
                                .padding(4.dp)
                                .size(16.dp),
                        )
                    }
                    Text(
                        displayStrength ?: "strength — see below",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (displayStrength != null) Muted else Amber,
                    )
                }
                if (needsResolve) TagChip("⚠ Re-check", Amber, AmberBg) else TagChip("✓ Matched drug list", Green, GreenSoft)
            }

            Spacer(Modifier.height(12.dp))

            // From your paper
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FROM YOUR PAPER", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Muted)
                Spacer(Modifier.width(8.dp))
                InkChip(med.ink)
            }

            Spacer(Modifier.height(12.dp))
            InfoRow(Icons.Outlined.Schedule, "Schedule", displaySchedule, valueMissing = false)
            Spacer(Modifier.height(8.dp))
            InfoRow(Icons.Outlined.Restaurant, "Food", displayFood, valueMissing = false)
            Spacer(Modifier.height(8.dp))
            InfoRow(
                Icons.Outlined.CalendarMonth, "Duration",
                durationText ?: "days — see below",
                valueMissing = durationText == null,
            )

            // PRN note
            if (med.prn) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GreenTint)
                        .padding(12.dp),
                ) {
                    Text(
                        "No reminders for this one — your prescription says “when needed”. You can log a dose from Today whenever you take it.",
                        fontSize = 12.5.sp, lineHeight = 18.sp, color = Muted, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Re-check flag: stays visible (and revisable) until the card is confirmed.
            if (med.flag != null) {
                Spacer(Modifier.height(12.dp))
                FlagBox(
                    med = med,
                    resolvedValue = resolvedValue,
                    onSave = onResolve,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Actions: read-aloud + confirm
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, PaperLine, CircleShape)
                        .clickable { },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read this medicine aloud",
                        tint = Muted, modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (needsResolve) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(PaperLine.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Resolve the re-check first", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Faint)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Green)
                            .clickable(onClick = onConfirm),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Matches my paper", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = White)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Always available — even a high-confidence reading can be wrong.
            // Attaches the prescription photo from local storage (device-to-doctor).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, PaperLine, RoundedCornerShape(14.dp))
                    .clickable {
                        val question = if (med.flag != null) {
                            "Hi doctor — checking my prescription from 11 Jul (photo attached): could you confirm the " +
                                (if (med.flag.kind == FlagKind.STRENGTH) "strength" else "number of days") +
                                " for $displayName? RxScan couldn’t read it clearly."
                        } else {
                            "Hi doctor — quick check on my prescription from 11 Jul (photo attached): " +
                                "I read $displayName as “${med.ink}”. Did I get that right?"
                        }
                        DoctorShare.askDoctor(context, question)
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = Green, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message your doctor — photo attached", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Green)
            }
        }
    }

    if (editing) {
        EditMedDialog(
            name = displayName,
            strength = if (strengthEditableHere) displayStrength else null,
            strengthEditable = strengthEditableHere,
            schedule = displaySchedule,
            food = displayFood,
            duration = if (durationEditableHere) durationText else null,
            durationEditable = durationEditableHere,
            onDismiss = { editing = false },
            onSave = { edit ->
                onMetaSave(edit)
                editing = false
            },
        )
    }
}

/**
 * Edit what we read (PRD §6: the user corrects the reading; free text accepted —
 * formulary autocomplete arrives with the real backend). Prefilled with the
 * currently displayed reading, which the user is correcting.
 */
@Composable
private fun EditMedDialog(
    name: String,
    strength: String?,
    strengthEditable: Boolean,
    schedule: String,
    food: String,
    duration: String?,
    durationEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (MedEdit) -> Unit,
) {
    var nameText by remember { mutableStateOf(name) }
    var strengthText by remember { mutableStateOf(strength ?: "") }
    var scheduleText by remember { mutableStateOf(schedule) }
    var foodText by remember { mutableStateOf(food) }
    var durationText by remember { mutableStateOf(duration ?: "") }
    val valid = nameText.trim().length >= 2 && scheduleText.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = White,
        title = { Text("Edit what we read", fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Correct anything to exactly what your paper says.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Medicine name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (strengthEditable) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = strengthText,
                        onValueChange = { strengthText = it },
                        label = { Text("Strength (e.g. 625 mg)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The strength is being re-checked on the card — type it there.",
                        fontSize = 12.sp, color = Amber,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = scheduleText,
                    onValueChange = { scheduleText = it },
                    label = { Text("Schedule (e.g. Morning · Night)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = foodText,
                    onValueChange = { foodText = it },
                    label = { Text("Food (e.g. After food)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (durationEditable) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        label = { Text("Duration (e.g. 5 days)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The number of days is being re-checked on the card — type it there.",
                        fontSize = 12.sp, color = Amber,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        MedEdit(
                            name = nameText.trim(),
                            strength = if (strengthEditable) strengthText.trim().ifEmpty { null } else null,
                            schedule = scheduleText.trim(),
                            food = foodText.trim(),
                            duration = if (durationEditable) durationText.trim().ifEmpty { null } else null,
                        ),
                    )
                },
                enabled = valid,
            ) {
                Text("Save", color = if (valid) Green else Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Muted, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun FlagBox(
    med: Medication,
    resolvedValue: String?,
    onSave: (String) -> Unit,
) {
    val flag = med.flag ?: return
    // Seeded ONLY with the user's own previous entry (never a system value).
    var typed by rememberSaveable(med.id) { mutableStateOf(resolvedValue ?: "") }
    val valid = when (flag.kind) {
        FlagKind.DURATION -> typed.trim().matches(Regex("^\\d{1,2}$")) && typed.trim().toInt() in 1..60
        FlagKind.STRENGTH -> typed.trim().length >= 2
        FlagKind.OTHER -> typed.trim().isNotEmpty()
    }
    val savedCurrent = resolvedValue != null && typed.trim() == resolvedValue

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AmberBg)
            .border(1.dp, AmberLine, RoundedCornerShape(14.dp))
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Amber, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(flag.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Amber)
        }
        Spacer(Modifier.height(6.dp))
        Text(flag.body, fontSize = 12.5.sp, lineHeight = 18.sp, color = Amber)
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // EMPTY until the user types — we never pre-fill a system value (CDSCO firewall).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(White)
                    .border(1.dp, if (savedCurrent) Green.copy(alpha = 0.5f) else AmberLine, RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = if (flag.kind == FlagKind.DURATION) it.filter(Char::isDigit).take(2) else it },
                    textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
                    keyboardOptions = if (flag.kind == FlagKind.DURATION)
                        KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (typed.isEmpty()) {
                    Text(flag.placeholder, fontSize = 13.5.sp, color = Muted)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (savedCurrent) {
                Row(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(GreenTint)
                        .border(1.dp, Green.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Green, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Saved", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            } else {
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (valid) Amber else AmberLine)
                        .clickable(enabled = valid) { onSave(typed.trim()) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (valid) White else Color(0xFFB99B54))
                }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueMissing: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Faint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label.uppercase(),
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            color = Faint, modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (valueMissing) Amber else TextPrimary,
        )
    }
}

private fun displayStrength(med: Medication, resolvedValue: String?, strengthEdit: String?): String? = when {
    med.flag?.kind == FlagKind.STRENGTH -> resolvedValue // flag box owns this field
    strengthEdit != null -> strengthEdit
    else -> med.strength
}

private fun displayDuration(med: Medication, resolvedValue: String?, durationEdit: String?): String? = when {
    med.flag?.kind == FlagKind.DURATION ->
        resolvedValue?.let { "$it days · ends ${endDate(it.toInt())}" } // flag box owns this field
    durationEdit != null -> durationEdit
    else -> med.duration
}

/** Design convention: day 1 = the scan day (11 Jul 2026 in the demo). */
private fun endDate(days: Int): String {
    val d = LocalDate.of(2026, 7, 11).plusDays((days - 1).toLong())
    return d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
}
