package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface MessageDao {

    @Transaction
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    LiveData<List<MessageWithAttachments>> observeByConversation(String conversationId);

    @Transaction
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    List<MessageWithAttachments> getByConversationSync(String conversationId);

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT 1")
    MessageEntity getLatestByConversationSync(String conversationId);

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    MessageEntity getByIdSync(String messageId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MessageEntity entity);

    @Query("UPDATE messages SET content = :content, updated_at = :updatedAt WHERE id = :messageId")
    void updateContent(String messageId, String content, long updatedAt);

    @Query("DELETE FROM messages WHERE id = :messageId")
    void deleteById(String messageId);

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND created_at >= :fromCreatedAt")
    void deleteFromTimestampSync(String conversationId, long fromCreatedAt);
}
