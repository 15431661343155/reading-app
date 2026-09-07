package com.example.readingapp.dto;

import com.example.readingapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneId;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private Long id;
    private String title;
    private String content;
    private String type;
    private Boolean read;
    private Long createdAt;

    public static MessageResponse fromEntity(Message message) {
        MessageResponse r = new MessageResponse();
        r.setId(message.getId());
        r.setTitle(message.getTitle());
        r.setContent(message.getContent());
        r.setType(message.getType());
        r.setRead(message.getIsRead());
        if (message.getCreatedAt() != null) {
            r.setCreatedAt(message.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return r;
    }
}
