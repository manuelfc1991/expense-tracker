package com.manuel.ours.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.manuel.ours.BuildConfig
import com.manuel.ours.data.db.BudgetDao
import com.manuel.ours.data.db.MemberDao
import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.db.ReminderDao
import com.manuel.ours.data.db.SharedRuleDao
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.BackupMerge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the whole-history backup file. See [BackupFile] for what is in it and
 * what deliberately is not.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val txnDao: TransactionDao,
    private val sharedRuleDao: SharedRuleDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val budgetDao: BudgetDao,
    private val memberDao: MemberDao,
    private val reminderDao: ReminderDao,
    private val prefs: AppPrefs,
    private val repository: TransactionRepository,
) {

    data class RestoreReport(
        val plan: BackupMerge.Plan,
        val rules: Int,
        val merchantRules: Int,
        val budgets: Int,
        val members: Int,
        val reminders: Int,
        val trackingStartApplied: Boolean,
    )

    /** Builds the file's contents. Separated from writing it so a test can read it. */
    suspend fun build(): BackupFile = withContext(Dispatchers.IO) {
        BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            selfUid = prefs.selfUidOnce(),
            selfName = prefs.selfNameOnce(),
            householdId = prefs.householdIdOnce(),
            trackingStartAt = prefs.trackingStartAtOnce(),
            transactions = txnDao.allIncludingDeleted().map { it.toBackup() },
            sharedRules = sharedRuleDao.all().map { it.toBackup() },
            // Only the ones a person made. The seeded table is code and arrives with the
            // app; carrying it would bloat the file and freeze a copy of a list that is
            // meant to improve with each release.
            merchantRules = merchantRuleDao.userDefined().map { it.toBackup() },
            budgets = budgetDao.all().map { it.toBackup() },
            members = memberDao.all().map { it.toBackup() },
            reminders = reminderDao.all().map { it.toBackup() },
        )
    }

    /** Writes the backup to the cache and hands it to the share sheet. */
    suspend fun shareBackup(): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(), "ours-backup-${stamp()}.json")
        file.writeText(BackupCodec.encode(build()))
        withContext(Dispatchers.Main) { share(file) }
        file
    }

    suspend fun read(uri: Uri): BackupRead = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            return@withContext BackupRead.Unreadable(e.message ?: "could not open that file")
        } ?: return@withContext BackupRead.Unreadable("could not open that file")
        BackupCodec.decode(text)
    }

    /**
     * Applies a backup. Adds and reconciles; never clears, never overwrites blindly.
     *
     * Order matters. Shared rules and merchant rules go in **before** the transactions so
     * that anything the restore inserts is categorised by the household's own corrections
     * rather than by the seeded defaults.
     */
    suspend fun restore(file: BackupFile): RestoreReport = withContext(Dispatchers.IO) {
        val rules = restoreSharedRules(file)
        val merchants = restoreMerchantRules(file)
        val budgets = restoreBudgets(file)
        val members = restoreMembers(file)
        val reminders = restoreReminders(file)

        val plan = BackupMerge.plan(
            backup = file.transactions,
            local = txnDao.allIncludingDeleted(),
            backupSelfUid = file.selfUid,
            localSelfUid = prefs.selfUidOnce(),
            localSelfName = prefs.selfNameOnce(),
        )
        repository.applyRestore(plan.inserts + plan.updates)

        // Only when this phone has no cutoff of its own. A date set here is a decision
        // somebody made on this handset and outranks whatever the dead one held.
        val applyStart = file.trackingStartAt > 0L && prefs.trackingStartAtOnce() <= 0L
        if (applyStart) prefs.setTrackingStartAt(file.trackingStartAt)

        RestoreReport(plan, rules, merchants, budgets, members, reminders, applyStart)
    }

    /** Last-write-wins on `updatedAt`, exactly as the sync path treats these. */
    private suspend fun restoreSharedRules(file: BackupFile): Int {
        if (file.sharedRules.isEmpty()) return 0
        val local = sharedRuleDao.all().associateBy { it.type to it.ruleKey }
        val newer = file.sharedRules.filter { incoming ->
            val held = local[incoming.type to incoming.ruleKey]
            held == null || incoming.updatedAt > held.updatedAt
        }
        if (newer.isNotEmpty()) sharedRuleDao.upsertAll(newer.map { it.toEntity() })
        return newer.size
    }

    private suspend fun restoreMerchantRules(file: BackupFile): Int {
        if (file.merchantRules.isEmpty()) return 0
        // `pattern` is uniquely indexed, and the local row may be a seeded default the
        // person has since overridden. Only fill gaps.
        val held = merchantRuleDao.all().map { it.pattern }.toSet()
        val fresh = file.merchantRules.filterNot { it.pattern in held }
        fresh.forEach {
            merchantRuleDao.upsert(
                MerchantRuleEntity(pattern = it.pattern, category = it.category, userDefined = true)
            )
        }
        return fresh.size
    }

    private suspend fun restoreBudgets(file: BackupFile): Int {
        if (file.budgets.isEmpty()) return 0
        val held = budgetDao.all().map { it.categoryKey }.toSet()
        val fresh = file.budgets.filterNot { it.categoryKey in held }
        fresh.forEach { budgetDao.upsert(it.toEntity()) }
        return fresh.size
    }

    private suspend fun restoreMembers(file: BackupFile): Int {
        if (file.members.isEmpty()) return 0
        val held = memberDao.all().map { it.uid }.toSet()
        // `isSelf` is about the phone, not the person. Restoring it would give a
        // replacement handset two selves, so it is dropped and this phone keeps its own.
        val fresh = file.members.filterNot { it.uid in held }.map { it.toEntity().copy(isSelf = false) }
        if (fresh.isNotEmpty()) memberDao.upsertAll(fresh)
        return fresh.size
    }

    private suspend fun restoreReminders(file: BackupFile): Int {
        if (file.reminders.isEmpty()) return 0
        val held = reminderDao.all().map { it.id }.toSet()
        val fresh = file.reminders.filterNot { it.id in held }.map { it.toEntity() }
        if (fresh.isNotEmpty()) reminderDao.upsertAll(fresh)
        return fresh.size
    }

    private fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun stamp(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
        .withZone(ZoneId.of("Asia/Kolkata"))
        .format(Instant.now())

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Save ${file.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
