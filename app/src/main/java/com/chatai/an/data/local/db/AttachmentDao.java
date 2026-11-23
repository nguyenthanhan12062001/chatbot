package com.chatai.an.data.local.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

import java.util.List;

@Dao
public interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<AttachmentEntity> entities);
}
