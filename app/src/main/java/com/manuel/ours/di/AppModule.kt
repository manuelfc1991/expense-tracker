package com.manuel.ours.di

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.db.BudgetDao
import com.manuel.ours.data.db.DatabaseKey
import com.manuel.ours.data.db.MemberDao
import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.PeerCursorDao
import com.manuel.ours.data.db.ReminderDao
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.data.sync.LamportClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // net.zetetic:sqlcipher-android does NOT self-load its native library, unlike
        // the older android-database-sqlcipher artifact. Without this the app dies at
        // first query with an UnsatisfiedLinkError, and only at runtime — nothing at
        // compile time hints that it is missing.
        System.loadLibrary("sqlcipher")

        // Anyone upgrading still has the old plaintext file on disk; SQLCipher cannot
        // open it, so it goes before we try.
        DatabaseKey.deleteLegacyPlaintextDatabase(context)

        val factory = SupportOpenHelperFactory(DatabaseKey.getOrCreate(context))

        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideSyncEventDao(db: AppDatabase): SyncEventDao = db.syncEventDao()
    @Provides fun providePeerCursorDao(db: AppDatabase): PeerCursorDao = db.peerCursorDao()
    @Provides fun provideMerchantRuleDao(db: AppDatabase): MerchantRuleDao = db.merchantRuleDao()
    @Provides fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()
    @Provides fun provideMemberDao(db: AppDatabase): MemberDao = db.memberDao()
    @Provides fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()

    @Provides
    @Singleton
    fun provideAppPrefs(@ApplicationContext context: Context): AppPrefs = AppPrefs(context)

    /**
     * One clock per process. Two instances would each mint lamport values from their
     * own counter and produce colliding events that the merge cannot order.
     */
    @Provides
    @Singleton
    fun provideLamportClock(): LamportClock = LamportClock()

    @Provides
    @Singleton
    fun provideSmsParser(): SmsParser = SmsParser()
}
