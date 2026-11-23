package com.chatai.an.data.local.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                ConversationEntity.class,
                MessageEntity.class,
                AttachmentEntity.class,
                MemoryEntity.class,
                ProviderConfigEntity.class,
                ApiKeyEntity.class,
                AgentTaskEntity.class,
                LocalModelEntity.class
        },
        version = 5,
        exportSchema = false
)
public abstract class ChatDatabase extends RoomDatabase {
    private static volatile ChatDatabase instance;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN thinking_content TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN provider_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN access_type TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN is_error INTEGER NOT NULL DEFAULT 0");

            db.execSQL("CREATE TABLE IF NOT EXISTS `memories` ("
                    + "`id` TEXT NOT NULL, "
                    + "`category` TEXT NOT NULL, "
                    + "`conversation_id` TEXT, "
                    + "`content` TEXT NOT NULL, "
                    + "`enabled` INTEGER NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, "
                    + "`updated_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");

            db.execSQL("CREATE TABLE IF NOT EXISTS `provider_configs` ("
                    + "`provider_id` TEXT NOT NULL, "
                    + "`display_name` TEXT NOT NULL, "
                    + "`provider_type` TEXT NOT NULL, "
                    + "`protocol_type` TEXT NOT NULL, "
                    + "`base_url` TEXT NOT NULL, "
                    + "`enabled` INTEGER NOT NULL, "
                    + "`key_rotation_enabled` INTEGER NOT NULL, "
                    + "`fallback_model_id` TEXT NOT NULL, "
                    + "`custom_headers_json` TEXT NOT NULL, "
                    + "`last_connection_status` TEXT NOT NULL, "
                    + "`last_tested_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`provider_id`))");

            db.execSQL("CREATE TABLE IF NOT EXISTS `api_keys` ("
                    + "`id` TEXT NOT NULL, "
                    + "`provider_id` TEXT NOT NULL, "
                    + "`label` TEXT NOT NULL, "
                    + "`encrypted_key` TEXT NOT NULL, "
                    + "`masked_preview` TEXT NOT NULL, "
                    + "`enabled` INTEGER NOT NULL, "
                    + "`active` INTEGER NOT NULL, "
                    + "`last_error` TEXT NOT NULL, "
                    + "`cooldown_until` INTEGER NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_keys_provider_id` ON `api_keys` (`provider_id`)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `agent_tasks` ("
                    + "`id` TEXT NOT NULL, "
                    + "`goal` TEXT NOT NULL, "
                    + "`conversation_id` TEXT NOT NULL, "
                    + "`model_id` TEXT NOT NULL, "
                    + "`status` TEXT NOT NULL, "
                    + "`steps_json` TEXT NOT NULL, "
                    + "`final_result` TEXT NOT NULL, "
                    + "`error_message` TEXT NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, "
                    + "`updated_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");

            db.execSQL("CREATE TABLE IF NOT EXISTS `local_models` ("
                    + "`id` TEXT NOT NULL, "
                    + "`display_name` TEXT NOT NULL, "
                    + "`format` TEXT NOT NULL, "
                    + "`uri_string` TEXT NOT NULL, "
                    + "`file_size_bytes` INTEGER NOT NULL, "
                    + "`estimated_ram_mb` INTEGER NOT NULL, "
                    + "`runtime_status` TEXT NOT NULL, "
                    + "`imported_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN project_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE conversations ADD COLUMN compaction_summary TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE conversations ADD COLUMN compacted_until INTEGER NOT NULL DEFAULT 0");

            db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'GLOBAL'");
            db.execSQL("ALTER TABLE memories ADD COLUMN area TEXT NOT NULL DEFAULT 'SHARED'");
            db.execSQL("ALTER TABLE memories ADD COLUMN project_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 3");
            db.execSQL("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE memories ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE memories ADD COLUMN merged_into_id TEXT");
            db.execSQL("ALTER TABLE memories ADD COLUMN last_used_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE memories ADD COLUMN usage_count INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN tool_records_json TEXT NOT NULL DEFAULT '[]'");
            db.execSQL("ALTER TABLE messages ADD COLUMN follow_ups_json TEXT NOT NULL DEFAULT '[]'");
        }
    };

    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN timeline_json TEXT NOT NULL DEFAULT '[]'");
            db.execSQL("ALTER TABLE messages ADD COLUMN sources_json TEXT NOT NULL DEFAULT '[]'");
        }
    };

    public abstract ConversationDao conversationDao();

    public abstract MessageDao messageDao();

    public abstract AttachmentDao attachmentDao();

    public abstract MemoryDao memoryDao();

    public abstract ProviderConfigDao providerConfigDao();

    public abstract ApiKeyDao apiKeyDao();

    public abstract AgentTaskDao agentTaskDao();

    public abstract LocalModelDao localModelDao();

    public static ChatDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ChatDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    ChatDatabase.class,
                                    "chatai_local.db"
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                            .build();
                }
            }
        }
        return instance;
    }
}
