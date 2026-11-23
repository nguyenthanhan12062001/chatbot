package com.chatai.an.data.local.db;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class MessageWithAttachments {
    @Embedded
    public MessageEntity message;

    @Relation(
            parentColumn = "id",
            entityColumn = "message_id"
    )
    public List<AttachmentEntity> attachments;
}
