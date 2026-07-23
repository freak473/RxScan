# Reminder-Firing Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Saved prescriptions actually ring: exact-alarm scheduling from confirmed meds + meal times, a sounding actionable notification, adherence logging + sync, real permissions, Today/Progress on real data.

**Architecture:** Chained next-dose alarm (spec `docs/superpowers/specs/2026-07-23-reminder-plane-design.md`): pure `DosePlan` computes the next event, `ReminderScheduler` arms ONE alarm, receivers post/re-arm. Doses are computed, never stored; adherence events are the only new table.

**Tech Stack:** Kotlin, AlarmManager, NotificationCompat, Room (existing), DataStore (existing), Gson (existing), JUnit 4 (existing).

## Global Constraints

- Branch: `feat/reminder-plane` (already checked out; spec committed).
- Build/test command prefix (CLAUDE.md Toolchain): `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android` — never `./mvnw`, never bare `gradlew`.
- **No live AI/backend calls in automated tests** (CLAUDE.md). JVM unit tests only; alarms/notifications verified manually on the `rxscan_light` emulator (API 30).
- **CDSCO copy invariants:** never imperative ("your prescription says…", never "take your medicine"), no inferred indications, no suggested values anywhere.
- **Every git commit needs a CLAUDE.md status update** (hook-enforced). Each task's commit step includes it — a one-line progress touch is enough.
- minSdk 26, compile/target 36. Guard API 31+ (`canScheduleExactAlarms`) and 33+ (`POST_NOTIFICATIONS`) calls with `Build.VERSION.SDK_INT` checks.
- PRN meds never schedule. Denial of any permission never blocks save/schedule.

---

### Task 1: DosePlan — pure dose arithmetic + JVM tests

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/reminders/DosePlan.kt`
- Test: `android/app/src/test/java/com/rxscan/app/reminders/DosePlanTest.kt`

**Interfaces:**
- Consumes: `MedsPayloadDto`, `MedItemDto`, `MealTimesDto` from `com.rxscan.app.data.net` (existing, exact shapes in `PrescriptionDtos.kt` / `MeDtos.kt`).
- Produces (later tasks import these exactly): `RxSource(localId: Long, payload: MedsPayloadDto)` · `DoseInstance(doseId: String, medName: String, strength: String?, slot: String, mealTiming: String?, fireAt: LocalDateTime)` · `sealed interface ReminderEvent { val fireAt: LocalDateTime }` with `DoseFire(fireAt, doses: List<DoseInstance>)` and `CourseEndNotice(fireAt, medNames: List<String>)` · `object DosePlan { fun slotTime(slot, mealTiming, meals): LocalTime; fun dosesOn(date, rxs, meals): List<DoseInstance>; fun todaysDoses(today, rxs, meals): List<DoseInstance>; fun dueAt(now, rxs, meals, acted: Set<String>): List<DoseInstance>; fun nextEvent(now, rxs, meals): ReminderEvent?; const val DUE_SLACK_MINUTES = 90L }`

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/java/com/rxscan/app/reminders/DosePlanTest.kt` (mirrors `PayloadMapperTest.kt`'s plain-JUnit style):

```kotlin
package com.rxscan.app.reminders

import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedItemDto
import com.rxscan.app.data.net.MedsPayloadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val MEALS = MealTimesDto(breakfast = "08:00", lunch = "13:30", dinner = "20:30")

private fun med(
    name: String = "Amox",
    strength: String? = "500 mg",
    slots: List<String> = listOf("morning"),
    mealTiming: String? = "after_food",
    durationDays: Int? = 5,
    prn: Boolean = false,
) = MedItemDto(name, strength, slots, mealTiming, durationDays, prn)

private fun rx(
    vararg meds: MedItemDto,
    confirmedAt: String = "2026-07-23T10:00:00+05:30",
    localId: Long = 1,
) = RxSource(localId, MedsPayloadDto(schema = 1, meds = meds.toList(), confirmedAt = confirmedAt))

class DosePlanTest {

    @Test fun `slot time applies AC PC offsets`() {
        assertEquals(LocalTime.of(8, 30), DosePlan.slotTime("morning", "after_food", MEALS))
        assertEquals(LocalTime.of(7, 30), DosePlan.slotTime("morning", "before_food", MEALS))
        assertEquals(LocalTime.of(8, 0), DosePlan.slotTime("morning", null, MEALS))
        assertEquals(LocalTime.of(14, 0), DosePlan.slotTime("afternoon", "after_food", MEALS))
        assertEquals(LocalTime.of(20, 0), DosePlan.slotTime("night", "before_food", MEALS))
    }

    @Test fun `prn meds are never scheduled`() {
        val doses = DosePlan.dosesOn(LocalDate.of(2026, 7, 23), listOf(rx(med(prn = true))), MEALS)
        assertTrue(doses.isEmpty())
    }

    @Test fun `course window is day1 through day1 plus duration minus 1`() {
        val rxs = listOf(rx(med(durationDays = 5))) // day1 = 2026-07-23 → last = 2026-07-27
        assertTrue(DosePlan.dosesOn(LocalDate.of(2026, 7, 22), rxs, MEALS).isEmpty())
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2026, 7, 23), rxs, MEALS).size)
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2026, 7, 27), rxs, MEALS).size)
        assertTrue(DosePlan.dosesOn(LocalDate.of(2026, 7, 28), rxs, MEALS).isEmpty())
    }

    @Test fun `ongoing course never expires`() {
        val rxs = listOf(rx(med(durationDays = null)))
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2027, 7, 22), rxs, MEALS).size)
    }

    @Test fun `dose id is deterministic`() {
        val dose = DosePlan.dosesOn(LocalDate.of(2026, 7, 23), listOf(rx(med())), MEALS).single()
        assertEquals("1:0:2026-07-23:morning", dose.doseId)
    }

    @Test fun `nextEvent groups doses firing at the same instant across meds`() {
        val rxs = listOf(rx(med(name = "A"), med(name = "B"))) // both morning after_food 08:30
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 8, 0), rxs, MEALS) as DoseFire
        assertEquals(LocalDateTime.of(2026, 7, 23, 8, 30), event.fireAt)
        assertEquals(listOf("A", "B"), event.doses.map { it.medName })
    }

    @Test fun `nextEvent is strictly after now`() {
        val rxs = listOf(rx(med(durationDays = 1)))
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 8, 30), rxs, MEALS)
        assertNull(event) // 08:30 dose not "next" at exactly 08:30; duration-1 course has nothing after
    }

    @Test fun `course auto stops - no event after last dose`() {
        val rxs = listOf(rx(med(durationDays = 2)))
        assertNull(DosePlan.nextEvent(LocalDateTime.of(2026, 7, 24, 21, 0), rxs, MEALS))
    }

    @Test fun `dueAt honours slack window and acted set`() {
        val rxs = listOf(rx(med())) // fires 08:30
        val now = LocalDateTime.of(2026, 7, 23, 9, 45) // 75 min after
        assertEquals(1, DosePlan.dueAt(now, rxs, MEALS, emptySet()).size)
        assertTrue(DosePlan.dueAt(now, rxs, MEALS, setOf("1:0:2026-07-23:morning")).isEmpty())
        val late = LocalDateTime.of(2026, 7, 23, 10, 1) // 91 min after
        assertTrue(DosePlan.dueAt(late, rxs, MEALS, emptySet()).isEmpty())
    }

    @Test fun `dueAt spans midnight`() {
        val rxs = listOf(rx(med(slots = listOf("night"), mealTiming = null)))
        val meals = MealTimesDto(breakfast = "08:00", lunch = "13:30", dinner = "23:50")
        val now = LocalDateTime.of(2026, 7, 24, 0, 30)
        assertEquals(1, DosePlan.dueAt(now, rxs, meals, emptySet()).size)
    }

    @Test fun `course end notice fires evening before last day and loses ties to earlier dose fires`() {
        val rxs = listOf(rx(med(durationDays = 5))) // last day 07-27 → notice 07-26 at 20:45
        val notice = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 26, 20, 44), rxs, MEALS)
        // At 20:44 on the 26th the day's 08:30 dose already fired; the notice at 20:45
        // is earlier than tomorrow's dose, so it wins.
        assertTrue(notice is CourseEndNotice)
        assertEquals(LocalDateTime.of(2026, 7, 26, 20, 45), (notice as CourseEndNotice).fireAt)
        assertEquals(listOf("Amox"), notice.medNames)
        // Earlier the same day, the 08:30 dose wins over the evening notice:
        val morning = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 26, 8, 0), rxs, MEALS)
        assertTrue(morning is DoseFire)
    }

    @Test fun `duration one course never emits a past notice`() {
        val rxs = listOf(rx(med(durationDays = 1)))
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 7, 0), rxs, MEALS)
        assertTrue(event is DoseFire) // notice day would be 07-22 (past) — filtered, dose still fires
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android testDebugUnitTest --tests "com.rxscan.app.reminders.DosePlanTest"`
Expected: FAIL to compile — unresolved references `RxSource`, `DosePlan`, `DoseFire`, `CourseEndNotice`.

- [ ] **Step 3: Implement DosePlan**

`android/app/src/main/java/com/rxscan/app/reminders/DosePlan.kt`:

```kotlin
package com.rxscan.app.reminders

import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedsPayloadDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** One saved prescription, parsed. localId = Room PK; dose ids embed it. */
data class RxSource(val localId: Long, val payload: MedsPayloadDto)

data class DoseInstance(
    val doseId: String,      // "$rxLocalId:$medIndex:$date:$slot" — deterministic, never stored
    val medName: String,
    val strength: String?,
    val slot: String,        // "morning" | "afternoon" | "night"
    val mealTiming: String?, // "before_food" | "after_food" | null
    val fireAt: LocalDateTime,
)

sealed interface ReminderEvent { val fireAt: LocalDateTime }
data class DoseFire(override val fireAt: LocalDateTime, val doses: List<DoseInstance>) : ReminderEvent
data class CourseEndNotice(override val fireAt: LocalDateTime, val medNames: List<String>) : ReminderEvent

/**
 * Pure dose arithmetic (spec: reminder-plane design §Dose model). No Android deps —
 * fully JVM-testable. Day 1 = confirmedAt's date (PRD date convention). PRN never
 * schedules. durationDays == null and not PRN ⇒ ongoing, never expires.
 * AC/PC offsets: before_food = meal − 30 min, after_food = meal + 30 min.
 */
object DosePlan {
    const val DUE_SLACK_MINUTES = 90L

    // ponytail: 370-day scan horizon — covers any v1 course incl. the next dose of an
    // ongoing regimen; revisit only if year-long gaps between doses ever exist.
    private const val HORIZON_DAYS = 370L

    fun slotTime(slot: String, mealTiming: String?, meals: MealTimesDto): LocalTime {
        val meal = LocalTime.parse(
            when (slot) {
                "morning" -> meals.breakfast
                "afternoon" -> meals.lunch
                else -> meals.dinner
            },
        )
        return when (mealTiming) {
            "before_food" -> meal.minusMinutes(30)
            "after_food" -> meal.plusMinutes(30)
            else -> meal
        }
    }

    private fun day1(payload: MedsPayloadDto): LocalDate = OffsetDateTime.parse(payload.confirmedAt).toLocalDate()

    fun dosesOn(date: LocalDate, rxs: List<RxSource>, meals: MealTimesDto): List<DoseInstance> =
        rxs.flatMap { rx ->
            val start = day1(rx.payload)
            rx.payload.meds.flatMapIndexed { i, med ->
                val last = med.durationDays?.let { start.plusDays(it - 1L) }
                if (med.prn || date < start || (last != null && date > last)) {
                    emptyList()
                } else {
                    med.slots.map { slot ->
                        DoseInstance(
                            doseId = "${rx.localId}:$i:$date:$slot",
                            medName = med.name,
                            strength = med.strength,
                            slot = slot,
                            mealTiming = med.mealTiming,
                            fireAt = date.atTime(slotTime(slot, med.mealTiming, meals)),
                        )
                    }
                }
            }
        }.sortedBy { it.fireAt }

    fun todaysDoses(today: LocalDate, rxs: List<RxSource>, meals: MealTimesDto): List<DoseInstance> =
        dosesOn(today, rxs, meals)

    /** Doses whose fire time is within the last [DUE_SLACK_MINUTES] and not yet acted on. */
    fun dueAt(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto, acted: Set<String>): List<DoseInstance> {
        val from = now.minusMinutes(DUE_SLACK_MINUTES)
        return (dosesOn(now.toLocalDate().minusDays(1), rxs, meals) + dosesOn(now.toLocalDate(), rxs, meals))
            .filter { it.fireAt > from && it.fireAt <= now && it.doseId !in acted }
    }

    /** The single next thing to arm: earliest future dose fire or course-end notice. */
    fun nextEvent(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto): ReminderEvent? {
        var date = now.toLocalDate()
        val end = date.plusDays(HORIZON_DAYS)
        var nextFire: DoseFire? = null
        while (date <= end) {
            val future = dosesOn(date, rxs, meals).filter { it.fireAt > now }
            if (future.isNotEmpty()) {
                val at = future.minOf { it.fireAt }
                nextFire = DoseFire(at, future.filter { it.fireAt == at })
                break
            }
            date = date.plusDays(1)
        }
        val notice = nextCourseEndNotice(now, rxs, meals)
        return when {
            nextFire == null -> notice
            notice == null || nextFire.fireAt <= notice.fireAt -> nextFire
            else -> notice
        }
    }

    // "Your <name> course ends tomorrow": evening before the last dose day, at
    // dinner + 15 min. The +15 dodges an exact collision with a dinner-time dose
    // fire — a tie would be skipped by the chain's strictly-after semantics.
    private fun nextCourseEndNotice(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto): CourseEndNotice? =
        rxs.flatMap { rx ->
            val start = day1(rx.payload)
            rx.payload.meds.mapNotNull { med ->
                val days = med.durationDays
                if (days == null || med.prn) return@mapNotNull null
                val at = start.plusDays(days - 2L).atTime(LocalTime.parse(meals.dinner).plusMinutes(15))
                if (at > now) med.name to at else null
            }
        }.groupBy({ it.second }, { it.first })
            .minByOrNull { it.key }
            ?.let { CourseEndNotice(it.key, it.value) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android testDebugUnitTest --tests "com.rxscan.app.reminders.DosePlanTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit** (touch CLAUDE.md status line per project rule)

```bash
git add android/app/src/main/java/com/rxscan/app/reminders/DosePlan.kt \
        android/app/src/test/java/com/rxscan/app/reminders/DosePlanTest.kt CLAUDE.md
git commit -m "feat(reminders): DosePlan pure dose arithmetic + tests"
```

---

### Task 2: Room adherence slice

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/data/local/AdherenceEventEntity.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/local/AdherenceDao.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/AdherenceRepository.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/data/local/RxScanDatabase.kt` (version 2 + migration)
- Modify: `android/app/src/main/java/com/rxscan/app/data/local/PrescriptionDao.kt` (add `all()`, `updatePayload()`)
- Modify: `android/app/src/main/java/com/rxscan/app/data/PrescriptionRepository.kt` (holder `internal`, expose `all()`/`updatePayload()`, register migration)

**Interfaces:**
- Produces: `AdherenceEventEntity(id: Long, doseId: String, medName: String, action: String, at: String, pendingSync: Boolean)` · `AdherenceRepository(context) { suspend fun record(doseIds: List<String>, medNames: List<String>, action: String); suspend fun all(): List<AdherenceEventEntity>; suspend fun pending(): List<AdherenceEventEntity>; suspend fun markSynced(ids: List<Long>) }` · `PrescriptionRepository.all(): List<PrescriptionEntity>` · `PrescriptionRepository.updatePayload(localId: Long, payloadJson: String, updatedAt: String)` · `internal object RxScanDatabaseHolder` (usage unchanged).
- `action` values: `"taken" | "snoozed" | "skipped"` (exactly these strings — Task 6's status mapping depends on them).

- [ ] **Step 1: Create the entity**

`AdherenceEventEntity.kt`:

```kotlin
package com.rxscan.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Taken/Snoozed/Skipped response (spec §Adherence). doseId is DosePlan's
 * deterministic "$rxLocalId:$medIndex:$date:$slot" — no dose table exists.
 */
@Entity(tableName = "adherence_events")
data class AdherenceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val doseId: String,
    val medName: String,
    val action: String, // "taken" | "snoozed" | "skipped"
    val at: String,     // ISO-8601 OffsetDateTime
    val pendingSync: Boolean,
)
```

- [ ] **Step 2: Create the DAO**

`AdherenceDao.kt`:

```kotlin
package com.rxscan.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AdherenceDao {
    @Insert
    suspend fun insert(event: AdherenceEventEntity)

    @Query("SELECT * FROM adherence_events")
    suspend fun all(): List<AdherenceEventEntity>

    @Query("SELECT * FROM adherence_events WHERE pendingSync = 1")
    suspend fun pending(): List<AdherenceEventEntity>

    @Query("UPDATE adherence_events SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}
```

- [ ] **Step 3: Bump the database + migration; extend PrescriptionDao**

`RxScanDatabase.kt` — replace the whole file with:

```kotlin
package com.rxscan.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// exportSchema=false: dev-phase cache; MIGRATION_1_2 below is the first real migration
// (v1 installs exist on dev devices — don't wipe their saved prescriptions).
@Database(entities = [PrescriptionEntity::class, AdherenceEventEntity::class], version = 2, exportSchema = false)
abstract class RxScanDatabase : RoomDatabase() {
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun adherenceDao(): AdherenceDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `adherence_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`doseId` TEXT NOT NULL, `medName` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, `at` TEXT NOT NULL, " +
                "`pendingSync` INTEGER NOT NULL)",
        )
    }
}
```

`PrescriptionDao.kt` — add two methods inside the interface:

```kotlin
    @Query("SELECT * FROM prescriptions")
    suspend fun all(): List<PrescriptionEntity>

    @Query("UPDATE prescriptions SET payloadJson = :payloadJson, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun updatePayload(localId: Long, payloadJson: String, updatedAt: String)
```

- [ ] **Step 4: Repository layer**

`PrescriptionRepository.kt` — change `private object RxScanDatabaseHolder` to `internal object RxScanDatabaseHolder`, register the migration in the builder:

```kotlin
        instance ?: Room.databaseBuilder(context.applicationContext, RxScanDatabase::class.java, "rxscan.db")
            .addMigrations(MIGRATION_1_2)
            .build().also { instance = it }
```

(add `import com.rxscan.app.data.local.MIGRATION_1_2`), and add to the class:

```kotlin
    suspend fun all(): List<PrescriptionEntity> = dao.all()

    suspend fun updatePayload(localId: Long, payloadJson: String, updatedAt: String) {
        dao.updatePayload(localId, payloadJson, updatedAt)
    }
```

Create `AdherenceRepository.kt`:

```kotlin
package com.rxscan.app.data

import android.content.Context
import com.rxscan.app.data.local.AdherenceEventEntity
import java.time.OffsetDateTime

/** Device-first adherence log (spec §Adherence); synced inside the opaque payload. */
class AdherenceRepository(context: Context) {
    private val dao = RxScanDatabaseHolder.get(context).adherenceDao()

    suspend fun record(doseIds: List<String>, medNames: List<String>, action: String) {
        val at = OffsetDateTime.now().toString()
        doseIds.forEachIndexed { i, id ->
            dao.insert(
                AdherenceEventEntity(
                    doseId = id,
                    medName = medNames.getOrElse(i) { "" },
                    action = action,
                    at = at,
                    pendingSync = true,
                ),
            )
        }
    }

    suspend fun all(): List<AdherenceEventEntity> = dao.all()
    suspend fun pending(): List<AdherenceEventEntity> = dao.pending()
    suspend fun markSynced(ids: List<Long>) = dao.markSynced(ids)
}
```

- [ ] **Step 5: Verify it compiles (Room validates DAOs at compile time)**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug`
Expected: BUILD SUCCESSFUL. (No JVM test for DAOs — Room needs a device; declarative queries are compile-checked.)

- [ ] **Step 6: Commit** (touch CLAUDE.md)

```bash
git add android/app/src/main/java/com/rxscan/app/data CLAUDE.md
git commit -m "feat(reminders): adherence_events table, DAO, repository, db v2 migration"
```

---

### Task 3: Alarm + notification plumbing (channels, notifier, receivers, scheduler, manifest)

One cohesive task — these units only make sense together and none is JVM-testable.

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/reminders/Channels.kt`
- Create: `android/app/src/main/java/com/rxscan/app/reminders/DoseNotifier.kt`
- Create: `android/app/src/main/java/com/rxscan/app/reminders/ReminderScheduler.kt`
- Create: `android/app/src/main/java/com/rxscan/app/reminders/Receivers.kt` (all three BroadcastReceivers — thin, related, one file)
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/rxscan/app/MainActivity.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt` (reschedule at the two data-change points)

**Interfaces:**
- Consumes: Task 1's `DosePlan`/`RxSource`/`DoseInstance`/`DoseFire`/`CourseEndNotice`; Task 2's `AdherenceRepository`, `PrescriptionRepository.all()`.
- Produces: `ReminderScheduler { suspend fun reschedule(context); fun canExact(context): Boolean; fun armSnooze(context, doseIds: Array<String>, medNames: Array<String>, slot: String, notifId: Int); suspend fun loadSources(context): Pair<List<RxSource>, MealTimesDto>? }` · `DoseNotifier { fun postDoseNotification(context, doses: List<DoseInstance>); fun postCourseEndNotice(context, medNames: List<String>); fun notifIdFor(date: LocalDate, slot: String): Int; fun slotLabel(slot: String): String }` · constants `CHANNEL_DOSES = "dose_reminders"`, `CHANNEL_COURSE = "course_updates"`, `EXTRA_DOSE_IDS/EXTRA_MED_NAMES/EXTRA_SLOT/EXTRA_NOTIF_ID`, `ACTION_TAKEN/ACTION_SNOOZE/ACTION_SKIP`.

- [ ] **Step 1: Channels**

`Channels.kt`:

```kotlin
package com.rxscan.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val CHANNEL_DOSES = "dose_reminders"   // high importance → system-default sound
const val CHANNEL_COURSE = "course_updates"  // default importance — notices must not ring like doses

/** Idempotent — createNotificationChannel is a no-op for an existing id. */
fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_DOSES, "Dose reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Rings when your prescription's doses are due" },
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_COURSE, "Course updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A course ending soon" },
    )
}
```

- [ ] **Step 2: DoseNotifier**

`DoseNotifier.kt`:

```kotlin
package com.rxscan.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rxscan.app.MainActivity
import java.time.LocalDate

const val EXTRA_DOSE_IDS = "dose_ids"
const val EXTRA_MED_NAMES = "med_names"
const val EXTRA_SLOT = "slot"
const val EXTRA_NOTIF_ID = "notif_id"
const val ACTION_TAKEN = "com.rxscan.app.TAKEN"
const val ACTION_SNOOZE = "com.rxscan.app.SNOOZE"
const val ACTION_SKIP = "com.rxscan.app.SKIP"

/**
 * Builds and posts the grouped dose notification (spec §Notification). Lock screen
 * stays discreet: publicVersion carries a count, never names (PRD notification privacy).
 * Copy is non-advisory: names what the prescription schedules, never an instruction.
 */
object DoseNotifier {

    fun notifIdFor(date: LocalDate, slot: String): Int = "$date:$slot".hashCode()

    fun slotLabel(slot: String): String = when (slot) {
        "morning" -> "Morning"
        "afternoon" -> "Afternoon"
        else -> "Night"
    }

    fun postDoseNotification(context: Context, doses: List<DoseInstance>) {
        if (doses.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return // denial: Today's banner is the recovery path
        ensureChannels(context)

        val slot = doses.first().slot
        val id = notifIdFor(doses.first().fireAt.toLocalDate(), slot)
        val names = doses.joinToString(" · ") { it.medName }
        // ponytail: platform bell icon — swap for a branded small icon with the asset pass.
        val smallIcon = android.R.drawable.ic_popup_reminder

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_DOSES)
            .setSmallIcon(smallIcon)
            .setContentTitle("${slotLabel(slot)} medicines · ${doses.size} due")
            .build()

        val doseIds = doses.map { it.doseId }.toTypedArray()
        val medNames = doses.map { it.medName }.toTypedArray()
        fun action(label: String, act: String, req: Int): NotificationCompat.Action {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(act)
                .putExtra(EXTRA_DOSE_IDS, doseIds)
                .putExtra(EXTRA_MED_NAMES, medNames)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_NOTIF_ID, id)
            return NotificationCompat.Action(
                0, label,
                PendingIntent.getBroadcast(
                    context, id * 10 + req, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        val tap = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) // cold-start routing (Task 6) lands on Today whenever a saved Rx exists

        val notification = NotificationCompat.Builder(context, CHANNEL_DOSES)
            .setSmallIcon(smallIcon)
            .setContentTitle("${slotLabel(slot)} medicines")
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(action("Taken", ACTION_TAKEN, 1))
            .addAction(action("Snooze 30m", ACTION_SNOOZE, 2))
            .addAction(action("Skip", ACTION_SKIP, 3))
            .build()
        nm.notify(id, notification)
    }

    fun postCourseEndNotice(context: Context, medNames: List<String>) {
        if (medNames.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val names = medNames.joinToString(" · ")
        nm.notify(
            ("notice:$names").hashCode(),
            NotificationCompat.Builder(context, CHANNEL_COURSE)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Course ends tomorrow")
                .setContentText("Your $names course ends tomorrow.")
                .setAutoCancel(true)
                .build(),
        )
    }
}
```

- [ ] **Step 3: ReminderScheduler**

`ReminderScheduler.kt`:

```kotlin
package com.rxscan.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.rxscan.app.data.PrescriptionRepository
import com.rxscan.app.data.local.RxScanStore
import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedsPayloadDto
import com.rxscan.app.data.net.PreferencesPayloadDto
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Arms ONE alarm for the next reminder event — the chained next-dose design
 * (spec §Scheduling). Call it from anywhere, anytime; it recomputes and re-arms.
 * ponytail: force-stop pauses the chain until next app-open/boot — every reminder
 * app shares this; the deferred battery-exemption prompt is the upgrade path.
 */
object ReminderScheduler {
    const val ACTION_DOSE_FIRE = "com.rxscan.app.DOSE_FIRE"
    const val ACTION_NOTICE_FIRE = "com.rxscan.app.NOTICE_FIRE"
    const val ACTION_SNOOZE_FIRE = "com.rxscan.app.SNOOZE_FIRE"

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Everything DosePlan needs, loaded. Null until meal times exist (pre-mealtimes screens). */
    suspend fun loadSources(context: Context): Pair<List<RxSource>, MealTimesDto>? {
        val gson = Gson()
        val meals = RxScanStore(context).loadMealTimesJson()
            ?.let { gson.fromJson(it, PreferencesPayloadDto::class.java).mealTimes } ?: return null
        val rxs = PrescriptionRepository(context).all().map {
            RxSource(it.localId, gson.fromJson(it.payloadJson, MedsPayloadDto::class.java))
        }
        return rxs to meals
    }

    suspend fun reschedule(context: Context) {
        val (rxs, meals) = loadSources(context) ?: return
        val event = DosePlan.nextEvent(LocalDateTime.now(), rxs, meals)
        val am = context.getSystemService(AlarmManager::class.java)

        fun chainPi(action: String, fill: Intent.() -> Intent = { this }): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DoseAlarmReceiver::class.java).setAction(action).fill(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // One chain alarm total: cancel both variants before arming (they differ by action,
        // so they are distinct PendingIntents).
        am.cancel(chainPi(ACTION_DOSE_FIRE))
        am.cancel(chainPi(ACTION_NOTICE_FIRE))
        if (event == null) return // course over — auto-stop, nothing armed

        val pi = when (event) {
            is CourseEndNotice -> chainPi(ACTION_NOTICE_FIRE) {
                putExtra(EXTRA_MED_NAMES, event.medNames.toTypedArray())
            }
            is DoseFire -> chainPi(ACTION_DOSE_FIRE)
        }
        arm(am, context, event.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun armSnooze(context: Context, doseIds: Array<String>, medNames: Array<String>, slot: String, notifId: Int) {
        val intent = Intent(context, DoseAlarmReceiver::class.java)
            .setAction(ACTION_SNOOZE_FIRE)
            .putExtra(EXTRA_DOSE_IDS, doseIds)
            .putExtra(EXTRA_MED_NAMES, medNames)
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_NOTIF_ID, notifId)
        val pi = PendingIntent.getBroadcast(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        arm(context.getSystemService(AlarmManager::class.java), context, System.currentTimeMillis() + 30 * 60_000L, pi)
    }

    private fun arm(am: AlarmManager, context: Context, atMillis: Long, pi: PendingIntent) {
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Degraded fallback (spec: user-approved deviation from the PRD's WorkManager
            // windows): same chain, inexact delivery — Today shows the accuracy note.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}
```

- [ ] **Step 4: Receivers**

`Receivers.kt`:

```kotlin
package com.rxscan.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.rxscan.app.data.AdherenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Runs [block] off the main thread inside the receiver's goAsync window. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** The chain alarm (dose fire / course-end notice) and snooze re-posts land here. */
class DoseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            ReminderScheduler.ACTION_NOTICE_FIRE -> {
                DoseNotifier.postCourseEndNotice(
                    context, intent.getStringArrayExtra(EXTRA_MED_NAMES)?.toList() ?: emptyList(),
                )
                ReminderScheduler.reschedule(context)
            }
            ReminderScheduler.ACTION_SNOOZE_FIRE -> {
                // Snooze re-post rebuilds from extras — short-lived, staleness acceptable (spec).
                val ids = intent.getStringArrayExtra(EXTRA_DOSE_IDS) ?: emptyArray()
                val names = intent.getStringArrayExtra(EXTRA_MED_NAMES) ?: emptyArray()
                val slot = intent.getStringExtra(EXTRA_SLOT) ?: "morning"
                val now = LocalDateTime.now()
                DoseNotifier.postDoseNotification(
                    context,
                    ids.mapIndexed { i, id -> DoseInstance(id, names.getOrElse(i) { "" }, null, slot, null, now) },
                )
                // No reschedule: the snooze alarm is a one-off outside the chain.
            }
            else -> { // ACTION_DOSE_FIRE — never trust stale extras: recompute what's due now
                val sources = ReminderScheduler.loadSources(context)
                if (sources != null) {
                    val acted = AdherenceRepository(context).all().map { it.doseId }.toSet()
                    DoseNotifier.postDoseNotification(
                        context, DosePlan.dueAt(LocalDateTime.now(), sources.first, sources.second, acted),
                    )
                }
                ReminderScheduler.reschedule(context)
            }
        }
    }
}

/** Taken / Snooze 30m / Skip from the notification — group-level (all doses in it). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_TAKEN -> "taken"
            ACTION_SNOOZE -> "snoozed"
            ACTION_SKIP -> "skipped"
            else -> return
        }
        val doseIds = intent.getStringArrayExtra(EXTRA_DOSE_IDS) ?: return
        val medNames = intent.getStringArrayExtra(EXTRA_MED_NAMES) ?: emptyArray()
        val slot = intent.getStringExtra(EXTRA_SLOT) ?: "morning"
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(context).cancel(notifId)
        async {
            AdherenceRepository(context).record(doseIds.toList(), medNames.toList(), action)
            if (action == "snoozed") ReminderScheduler.armSnooze(context, doseIds, medNames, slot, notifId)
        }
    }
}

/** Re-arm the chain after reboot or clock/zone changes. All three are protected broadcasts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        ReminderScheduler.reschedule(context)
    }
}
```

- [ ] **Step 5: Manifest**

In `android/app/src/main/AndroidManifest.xml`, add above `<application>`:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

(`SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM` — Play policy reserves the latter for alarm-clock/calendar apps.)

Inside `<application>`, alongside the activity:

```xml
        <receiver android:name=".reminders.DoseAlarmReceiver" android:exported="false" />
        <receiver android:name=".reminders.NotificationActionReceiver" android:exported="false" />
        <receiver android:name=".reminders.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
```

(Boot receiver `exported="true"` is safe: all three actions are protected broadcasts only the system can send.)

- [ ] **Step 6: Call sites**

`MainActivity.kt` — add imports `androidx.lifecycle.lifecycleScope`, `com.rxscan.app.reminders.ReminderScheduler`, `kotlinx.coroutines.launch`, and after `super.onCreate(savedInstanceState)`:

```kotlin
        lifecycleScope.launch { ReminderScheduler.reschedule(this@MainActivity) }
```

`RxScanNav.kt` — two insertions (import `com.rxscan.app.reminders.ReminderScheduler`):

In the `MEAL_TIMES` composable's `onSave`, replace `scope.launch { store.saveMealTimesJson(gson.toJson(payload)) }` with (meal-time edits move every fire time):

```kotlin
                scope.launch {
                    store.saveMealTimesJson(gson.toJson(payload))
                    ReminderScheduler.reschedule(context)
                }
```

In the `NOTIF_PERM` composable's `onResult`, inside the existing `scope.launch`, before the navigate calls (the PRD save moment — meds + meal times both exist now; runs on the skip-login path too):

```kotlin
                    ReminderScheduler.reschedule(context)
```

- [ ] **Step 7: Build**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit** (touch CLAUDE.md)

```bash
git add android/app/src/main CLAUDE.md
git commit -m "feat(reminders): alarm chain, channels, dose notification, receivers, manifest"
```

---

### Task 4: Real permissions — NotifPermScreen + Today banner heal

**Files:**
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/NotifPermScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/TodayScreen.kt` (banner heal + accuracy note only — the data rewire is Task 6)

**Interfaces:**
- Consumes: `ReminderScheduler.canExact(context)` (Task 3).
- Produces: `NotifPermScreen(onResult: (allowed: Boolean) -> Unit)` — signature unchanged; `TodayScreen` signature unchanged.

- [ ] **Step 1: Wire the real POST_NOTIFICATIONS request**

In `NotifPermScreen.kt`, add imports:

```kotlin
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rxscan.app.reminders.ReminderScheduler
```

(If `androidx.lifecycle.compose.LocalLifecycleOwner` doesn't resolve on this Compose BOM, use `androidx.compose.ui.platform.LocalLifecycleOwner` — one of the two exists; do not add a dependency.)

Replace `var exactOn by rememberSaveable { mutableStateOf(false) }` with:

```kotlin
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult(granted)
    }
    // Re-checked on resume so returning from the exact-alarm Settings screen updates the card.
    var exactGranted by remember { mutableStateOf(ReminderScheduler.canExact(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exactGranted = ReminderScheduler.canExact(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

(keep `mutableStateOf`/`getValue`/`setValue` imports; drop `rememberSaveable` if now unused).

The mock-dialog card keeps its visuals; only the two click handlers change. "Allow" (the green box's `.clickable { onResult(true) }`):

```kotlin
                                .clickable {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onResult(true) // ≤12 auto-grants (PRD: the step is API-gated)
                                    }
                                },
```

"Don't allow" stays `.clickable { onResult(false) }` (a primer decline never spends the system dialog's two-refusal budget).

- [ ] **Step 2: Make the exact-alarm card real and skippable**

Wrap the entire exact-alarm `PaperCard` block in:

```kotlin
        if (!exactGranted) {
            // ... existing PaperCard(...) unchanged except the Switch ...
        }
```

and replace the `Switch`'s `checked`/`onCheckedChange` with:

```kotlin
                Switch(
                    checked = false, // shown only while not granted; grant hides the card
                    onCheckedChange = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                    // colors block unchanged
                )
```

Update the file's KDoc: delete the "mocked inline" sentence; the dialog is now real and the exact-alarm card deep-links to Settings (skippable per PRD §6 step 4).

- [ ] **Step 3: Today banner heals on resume + accuracy note**

In `TodayScreen.kt`, add imports: `androidx.core.app.NotificationManagerCompat`, `androidx.compose.runtime.DisposableEffect`, `androidx.lifecycle.Lifecycle`, `androidx.lifecycle.LifecycleEventObserver`, `androidx.lifecycle.compose.LocalLifecycleOwner` (same fallback note as above), `com.rxscan.app.reminders.ReminderScheduler`.

After `val context = LocalContext.current`:

```kotlin
    // Live permission state: heals when the user returns from Settings (PRD §6.4).
    var notifEnabled by remember { mutableStateOf(notifAllowed) }
    var exactGranted by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                exactGranted = ReminderScheduler.canExact(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

Change the banner condition `if (!notifAllowed) {` to `if (!notifEnabled) {`, and immediately after the banner's closing brace + `Spacer` (still inside the scroll column), add the inexact-accuracy note (spec: fallback requires an in-app note):

```kotlin
            if (notifEnabled && !exactGranted) {
                Text(
                    "Exact-time alarms are off — reminders may arrive a few minutes late.",
                    fontSize = 12.sp, color = Muted,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
```

- [ ] **Step 4: Build**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (touch CLAUDE.md)

```bash
git add android/app/src/main/java/com/rxscan/app/ui/screens CLAUDE.md
git commit -m "feat(reminders): real POST_NOTIFICATIONS + exact-alarm step, healing banner"
```

---

### Task 5: Adherence sync inside the opaque payload

**Files:**
- Modify: `android/app/src/main/java/com/rxscan/app/data/net/PrescriptionDtos.kt` (additive field + entry DTO)
- Modify: `android/app/src/main/java/com/rxscan/app/data/net/PayloadMapper.kt` (merge function)
- Modify: `android/app/src/main/java/com/rxscan/app/data/SyncRepository.kt` (`pushAdherence()`)
- Modify: `android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt` (push on app open)
- Test: `android/app/src/test/java/com/rxscan/app/data/net/PayloadMapperTest.kt` (add merge tests)

**Interfaces:**
- Consumes: Task 2's `AdherenceRepository`, `PrescriptionRepository.all()/updatePayload()`.
- Produces: `AdherenceEntryDto(doseId: String, action: String, at: String)` · `MedsPayloadDto.adherence: List<AdherenceEntryDto>?` (default null — additive, old payloads unaffected) · `MedsPayloadDto.withAdherence(added: List<AdherenceEntryDto>): MedsPayloadDto` · `SyncRepository.pushAdherence(): SyncOutcome`.

- [ ] **Step 1: Write the failing merge tests**

Append inside the existing test class in `PayloadMapperTest.kt` (match its imports/style):

```kotlin
    @Test
    fun `withAdherence appends and dedupes`() {
        val payload = MedsPayloadDto(meds = emptyList(), confirmedAt = "2026-07-23T10:00:00+05:30")
        val e1 = AdherenceEntryDto("1:0:2026-07-23:morning", "taken", "2026-07-23T08:35:00+05:30")
        val once = payload.withAdherence(listOf(e1))
        assertEquals(listOf(e1), once.adherence)
        // Re-merging the same event (retry after a failed PATCH) must not duplicate:
        val twice = once.withAdherence(listOf(e1))
        assertEquals(1, twice.adherence!!.size)
        // A different action on the same dose is a distinct event:
        val e2 = AdherenceEntryDto("1:0:2026-07-23:morning", "snoozed", "2026-07-23T08:20:00+05:30")
        assertEquals(2, once.withAdherence(listOf(e2)).adherence!!.size)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android testDebugUnitTest --tests "com.rxscan.app.data.net.PayloadMapperTest"`
Expected: FAIL to compile — unresolved `AdherenceEntryDto`, `withAdherence`.

- [ ] **Step 3: Implement**

`PrescriptionDtos.kt` — add below `MedItemDto`:

```kotlin
/** One Taken/Snoozed/Skipped response riding inside the opaque payload (spec §Adherence). */
data class AdherenceEntryDto(val doseId: String, val action: String, val at: String)
```

and change `MedsPayloadDto` to:

```kotlin
data class MedsPayloadDto(
    val schema: Int = 1,
    val meds: List<MedItemDto>,
    val confirmedAt: String, // ISO8601
    val adherence: List<AdherenceEntryDto>? = null, // additive — server stores opaquely
)
```

`PayloadMapper.kt` — add at the bottom:

```kotlin
/** Merge new adherence events into the payload, deduping on (doseId, action, at) —
 *  a retried PATCH must not double-count doses. */
fun MedsPayloadDto.withAdherence(added: List<AdherenceEntryDto>): MedsPayloadDto {
    val existing = adherence ?: emptyList()
    val seen = existing.map { Triple(it.doseId, it.action, it.at) }.toHashSet()
    return copy(adherence = existing + added.filter { seen.add(Triple(it.doseId, it.action, it.at)) })
}
```

`SyncRepository.kt` — add imports (`com.rxscan.app.data.net.AdherenceEntryDto`, `com.rxscan.app.data.net.withAdherence`), a field next to the existing ones:

```kotlin
    private val adherence = AdherenceRepository(context)
```

and the method:

```kotlin
    /**
     * Push pending Taken/Snoozed/Skipped events inside the opaque payload via PATCH
     * (spec §Adherence: no dedicated endpoint until v2 needs the server to read them).
     * Events for a not-yet-POSTed rx (rxId == null) simply wait for the next call.
     */
    suspend fun pushAdherence(): SyncOutcome = try {
        val pending = adherence.pending()
        if (pending.isNotEmpty()) {
            val entities = prescriptions.all().associateBy { it.localId }
            pending.groupBy { it.doseId.substringBefore(':').toLongOrNull() }.forEach { (localId, events) ->
                val entity = localId?.let(entities::get) ?: return@forEach
                val rxId = entity.rxId ?: return@forEach
                val merged = gson.fromJson(entity.payloadJson, MedsPayloadDto::class.java)
                    .withAdherence(events.map { AdherenceEntryDto(it.doseId, it.action, it.at) })
                val res = Network.prescriptionApi.update(rxId, PrescriptionsPostRequestDto(merged))
                prescriptions.updatePayload(entity.localId, gson.toJson(merged), res.updatedAt)
                adherence.markSynced(events.map { it.id })
            }
        }
        SyncOutcome.Success(userCreated = false)
    } catch (e: HttpException) {
        if (e.code() == 401) {
            store.saveToken(null)
            SyncOutcome.AuthExpired
        } else {
            SyncOutcome.Failure // events stay pendingSync — next app open retries
        }
    } catch (_: IOException) {
        SyncOutcome.Network
    }
```

`RxScanNav.kt` — replace `LaunchedEffect(Unit) { store.loadToken() }` with:

```kotlin
    LaunchedEffect(Unit) {
        if (store.loadToken() != null) sync.pushAdherence() // opportunistic; failures retry next open
    }
```

- [ ] **Step 4: Run tests**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android testDebugUnitTest`
Expected: PASS (PayloadMapperTest + DosePlanTest).

- [ ] **Step 5: Commit** (touch CLAUDE.md)

```bash
git add android/app/src/main/java/com/rxscan/app/data android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt \
        android/app/src/test CLAUDE.md
git commit -m "feat(reminders): adherence events sync inside opaque payload PATCH"
```

---

### Task 6: Today + Progress on real doses; cold-start routing

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/reminders/UiData.kt` (Today/Progress data providers)
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/TodayScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/ProgressScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt` (route returning users to Today)

**Interfaces:**
- Consumes: `DosePlan.todaysDoses`, `AdherenceRepository.all()/record()`, `ReminderScheduler.loadSources/armSnooze`, `DoseNotifier.notifIdFor/slotLabel`.
- Produces: `UiDose(id, med, sub, time, slot)` · `UiDoseGroup(time, whenLabel, doses)` · `TodayUi(dateLabel, groups, statuses: Map<String, String>)` · `CourseUi(name, sub, days: List<String>)` · `suspend fun buildTodayUi(context): TodayUi?` (null ⇒ no saved Rx/meals — screens fall back to their MockData rendering) · `suspend fun buildProgressUi(context): Pair<List<CourseUi>, String>?` (courses + share-report text) · `suspend fun recordDoseAction(context, dose: UiDose, action: String)`.
- Status strings map onto the existing `StatusChip`: `"taken" | "snooze" | "skip" | "pending"`. Adherence actions are `"taken" | "snoozed" | "skipped"` — `statusOf` translates.

- [ ] **Step 1: Data providers**

`UiData.kt`:

```kotlin
package com.rxscan.app.reminders

import android.content.Context
import com.rxscan.app.data.AdherenceRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class UiDose(val id: String, val med: String, val sub: String, val time: String, val slot: String)
data class UiDoseGroup(val time: String, val whenLabel: String, val doses: List<UiDose>)
data class TodayUi(val dateLabel: String, val groups: List<UiDoseGroup>, val statuses: Map<String, String>)
data class CourseUi(val name: String, val sub: String, val days: List<String>)

private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

private fun mealName(slot: String) = when (slot) {
    "morning" -> "breakfast"
    "afternoon" -> "lunch"
    else -> "dinner"
}

private fun whenLabel(slot: String, mealTiming: String?) = when (mealTiming) {
    "before_food" -> "30 min before ${mealName(slot)}"
    "after_food" -> "after ${mealName(slot)}"
    else -> "at ${mealName(slot)} time"
}

/** taken/skip are terminal; snooze shows until one of them lands. */
private fun statusOf(events: List<String>): String = when {
    "taken" in events -> "taken"
    "skipped" in events -> "skip"
    "snoozed" in events -> "snooze"
    else -> "pending"
}

/** Null ⇒ nothing saved yet — the screen falls back to its MockData rendering. */
suspend fun buildTodayUi(context: Context): TodayUi? {
    val (rxs, meals) = ReminderScheduler.loadSources(context) ?: return null
    if (rxs.isEmpty()) return null
    val doses = DosePlan.todaysDoses(LocalDate.now(), rxs, meals)
    val eventsByDose = AdherenceRepository(context).all().groupBy { it.doseId }
    val groups = doses.groupBy { it.fireAt }.toSortedMap().map { (fireAt, group) ->
        UiDoseGroup(
            time = fireAt.toLocalTime().format(TIME_FMT),
            whenLabel = whenLabel(group.first().slot, group.first().mealTiming),
            doses = group.map { d ->
                UiDose(
                    id = d.doseId,
                    med = d.medName,
                    sub = listOfNotNull(d.strength, whenLabel(d.slot, d.mealTiming)).joinToString(" · "),
                    time = d.fireAt.toLocalTime().format(TIME_FMT),
                    slot = d.slot,
                )
            },
        )
    }
    val statuses = doses.associate { d ->
        d.doseId to statusOf(eventsByDose[d.doseId]?.map { it.action } ?: emptyList())
    }
    return TodayUi(LocalDate.now().format(DATE_FMT), groups, statuses)
}

/** Sheet + notification write the same log. Snooze arms the one-off re-ring. */
suspend fun recordDoseAction(context: Context, dose: UiDose, action: String) {
    AdherenceRepository(context).record(listOf(dose.id), listOf(dose.med), action)
    if (action == "snoozed") {
        ReminderScheduler.armSnooze(
            context, arrayOf(dose.id), arrayOf(dose.med), dose.slot,
            DoseNotifier.notifIdFor(LocalDate.now(), dose.slot),
        )
    }
}

/** Course day-chips + the doctor-report text, from real events. Null ⇒ mock fallback. */
suspend fun buildProgressUi(context: Context): Pair<List<CourseUi>, String>? {
    val (rxs, meals) = ReminderScheduler.loadSources(context) ?: return null
    if (rxs.isEmpty()) return null
    val events = AdherenceRepository(context).all().groupBy { it.doseId }
    val today = LocalDate.now()
    val courses = rxs.flatMap { rx ->
        val day1 = OffsetDateTime.parse(rx.payload.confirmedAt).toLocalDate()
        rx.payload.meds.withIndex()
            .filter { (_, med) -> !med.prn && med.slots.isNotEmpty() }
            .map { (medIndex, med) ->
                // ongoing (durationDays == null): show a rolling 5-day window ending +4
                val lastDay = med.durationDays?.let { day1.plusDays(it - 1L) } ?: today.plusDays(4)
                val days = generateSequence(day1) { it.plusDays(1) }.takeWhile { it <= lastDay }.map { date ->
                    val statuses = med.slots.map { slot ->
                        statusOf(events["${rx.localId}:$medIndex:$date:$slot"]?.map { e -> e.action } ?: emptyList())
                    }
                    when {
                        date > today -> "up"
                        date == today -> "today"
                        statuses.all { it == "taken" } -> "done"
                        else -> "miss"
                    }
                }.toList()
                CourseUi(
                    name = listOfNotNull(med.name, med.strength).joinToString(" "),
                    sub = med.slots.joinToString(" · ") { DoseNotifier.slotLabel(it) } +
                        (med.mealTiming?.let { " · " + it.replace("_food", " food") } ?: ""),
                    days = days,
                )
            }
    }
    val firstDay1 = rxs.minOf { OffsetDateTime.parse(it.payload.confirmedAt).toLocalDate() }
    val dayN = ChronoUnit.DAYS.between(firstDay1, today) + 1
    val report = buildString {
        appendLine("RxScan adherence report — day $dayN")
        courses.forEach { c ->
            val done = c.days.count { it == "done" }
            val missed = c.days.count { it == "miss" }
            appendLine("${c.name}: $done day(s) all taken, $missed day(s) with a missed dose")
        }
        append("Marked by the patient in RxScan. The prescription itself is the source of truth.")
    }
    return courses to report
}
```

- [ ] **Step 2: Rewire TodayScreen**

In `TodayScreen.kt` (on top of Task 4's changes):

Add imports: `androidx.compose.runtime.produceState`, `androidx.compose.runtime.rememberCoroutineScope`, `com.rxscan.app.reminders.TodayUi`, `com.rxscan.app.reminders.UiDose`, `com.rxscan.app.reminders.buildTodayUi`, `com.rxscan.app.reminders.recordDoseAction`, `kotlinx.coroutines.launch`.

Keep the private `Dose` class and mock `doseGroups` (they are the dev fallback until real extraction lands — spec §Today). After the Task 4 lifecycle block, add:

```kotlin
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val realUi by produceState<TodayUi?>(initialValue = null, refresh) {
        value = buildTodayUi(context)
    }
```

Replace the `val status = remember { mutableStateMapOf(...) }` block and the two lines computing `allDoses`/`taken`/`next` with:

```kotlin
    // Mock statuses only back the MockData fallback; real statuses come from realUi.
    val mockStatus = remember {
        mutableStateMapOf(
            "d1" to "taken", "d2" to "taken", "d3" to "taken", "d4" to "taken",
            "d5" to "pending", "d6" to "pending",
        )
    }
    val ui = realUi
    val groups = ui?.groups?.map { g ->
        Triple(g.time, g.whenLabel, g.doses.map { Dose(it.id, it.med, it.sub, it.time, it.slot) })
    } ?: doseGroups
    fun statusOf(id: String): String = ui?.statuses?.get(id) ?: mockStatus[id] ?: "pending"
    val allDoses = groups.flatMap { it.third }
    val taken = allDoses.count { statusOf(it.id) == "taken" }
    val next = allDoses.firstOrNull { statusOf(it.id) == "pending" }
```

(Note: the real mapping stores the slot in the private `Dose.group` field — `Dose(it.id, it.med, it.sub, it.time, it.slot)`.)

Then mechanical swaps through the body:
- every `status[dose.id] ?: "pending"` → `statusOf(dose.id)`
- the `doseGroups.forEach { (time, whenLabel, doses) ->` loop iterates `groups` instead
- the hardcoded header `Text("Saturday, 11 July", …)` → `Text(ui?.dateLabel ?: "Saturday, 11 July", …)`
- the all-done card's hardcoded "Next reminder tomorrow at 7:30 AM" stays (cosmetic).

Dose-sheet actions — replace the three `status[dose.id] = …; sheetDose = null` handlers with:

```kotlin
// Taken:
.clickable {
    if (ui != null) scope.launch { recordDoseAction(context, UiDose(dose.id, dose.med, dose.sub, dose.time, dose.group), "taken"); refresh++ }
    else mockStatus[dose.id] = "taken"
    sheetDose = null
},
// Snooze 30m:
.clickable {
    if (ui != null) scope.launch { recordDoseAction(context, UiDose(dose.id, dose.med, dose.sub, dose.time, dose.group), "snoozed"); refresh++ }
    else mockStatus[dose.id] = "snooze"
    sheetDose = null
},
// Skip:
.clickable {
    if (ui != null) scope.launch { recordDoseAction(context, UiDose(dose.id, dose.med, dose.sub, dose.time, dose.group), "skipped"); refresh++ }
    else mockStatus[dose.id] = "skip"
    sheetDose = null
},
```

- [ ] **Step 3: Rewire ProgressScreen**

In `ProgressScreen.kt`: keep `private data class Course` and the mock `courses` val (fallback). Add imports: `androidx.compose.runtime.getValue`, `androidx.compose.runtime.produceState`, `androidx.compose.ui.platform.LocalContext`, `com.rxscan.app.reminders.CourseUi`, `com.rxscan.app.reminders.buildProgressUi`. At the top of the composable body:

```kotlin
    val context = LocalContext.current
    val real by produceState<Pair<List<CourseUi>, String>?>(initialValue = null) {
        value = buildProgressUi(context)
    }
    val shownCourses = real?.first?.map { Course(it.name, it.sub, it.days) } ?: courses
```

Swap the body's iteration over `courses` → `shownCourses`. Replace the hardcoded report literal (around lines 187–190) with `real?.second ?: (<the existing literal, kept verbatim as the fallback>)` — read the file first and preserve the exact existing string as the fallback branch.

- [ ] **Step 4: Cold-start routing to Today**

`RxScanNav.kt` — replace the Task 5 `LaunchedEffect` with:

```kotlin
    LaunchedEffect(Unit) {
        // Returning user (incl. notification tap): saved doses ⇒ land on Today, not welcome.
        // ponytail: brief welcome flash before the jump — a splash route can absorb it later.
        val hasSession = store.loadToken() != null
        if (hasSession) sync.pushAdherence()
        if (prescriptions.all().isNotEmpty() && store.loadMealTimesJson() != null) {
            nav.navigate(Routes.TODAY) { popUpTo(Routes.WELCOME) { inclusive = true } }
        }
    }
```

- [ ] **Step 5: Build + unit tests**

Run: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Commit** (touch CLAUDE.md)

```bash
git add android/app/src/main CLAUDE.md
git commit -m "feat(reminders): Today/Progress on real doses, cold-start routing to Today"
```

---

### Task 7: Emulator smoke — the sound actually rings

Manual, per spec (§Testing) and CLAUDE.md (real behavior is validated via the app, not the test suite).

- [ ] **Step 1: Install on the light emulator**

```bash
~/Library/Android/sdk/emulator/emulator -avd rxscan_light \
  -gpu swiftshader_indirect -no-snapshot -no-boot-anim -memory 1536 -cores 2 &
# after boot:
ADB=~/Library/Android/sdk/platform-tools/adb
JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.rxscan.app/.MainActivity
```

- [ ] **Step 2: Walk the flow to Today** — welcome → consent → capture → verify (confirm all 4 mock meds; enter Pantocid strength + Ascoril days) → meal times (set dinner so a night dose lands ~5–10 min out, remembering the ±30 AC/PC offset) → skip sign-in → notif screen (API 30: no system dialog, tap Allow) → Today shows REAL doses derived from what was confirmed — header shows today's date, not "Saturday, 11 July".

- [ ] **Step 3: Hear it ring** — leave the app (home button), wait for the fire time. Expected: notification with the system default sound, med names, actions Taken / Snooze 30m / Skip. `$ADB exec-out screencap -p > shot.png` to capture.

- [ ] **Step 4: Actions write through** — tap **Taken** on the notification → reopen the app → that dose's chip shows "✓ Taken" on Today. Tap a dose row → sheet → **Snooze 30m** → chip shows Snoozed (and a re-ring is armed +30 min).

- [ ] **Step 5: Boot chain survives** — `$ADB reboot` → wait for boot (don't open the app) → `$ADB shell dumpsys alarm | grep -B1 -A3 rxscan` → an RTC_WAKEUP alarm for `com.rxscan.app` exists (BootReceiver re-armed).

- [ ] **Step 6: Lock-screen discretion** — with a dose pending, sleep the screen (`$ADB shell input keyevent KEYCODE_SLEEP`), fire, wake → lock screen shows "… medicines · N due" without drug names (if names appear, set lock-screen notifications to "hide sensitive content" in emulator Settings — API 30 only applies publicVersion then; note the result either way).

- [ ] **Step 7: Record results + commit**

Update CLAUDE.md "Current status": reminder plane built, smoke results (what rang, what didn't), any deviations. Commit:

```bash
git add CLAUDE.md
git commit -m "docs: reminder-plane smoke results"
```

---

## Self-Review (done at write time)

- **Spec coverage:** permissions (T4), channel + system-default sound (T3), scheduling + AC/PC offsets + auto-stop (T1/T3), boot/time-change re-arm (T3), discreet grouped actionable notification (T3), adherence log + payload sync (T2/T5), course-end notice (T1/T3), Today/Progress rewire + cold-start routing (T6), inexact fallback + accuracy note (T3/T4), manual smoke (T7). Spec's deferred list untouched.
- **Type consistency:** `RxSource`/`DoseInstance`/`DoseFire`/`CourseEndNotice` (T1) consumed verbatim in T3/T6; adherence action strings `taken/snoozed/skipped` (T2) translate to StatusChip's `taken/snooze/skip` in exactly one place (`statusOf`, T6).
- **Known judgment calls, flagged inline:** `LocalLifecycleOwner` import path varies by Compose BOM (both candidates given, no new dependency); ProgressScreen's fallback report literal must be preserved verbatim from the file; notification small icon is a platform drawable (`ponytail:` ceiling noted).
