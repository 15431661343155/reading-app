package com.example.readingapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCountResponse {
    private long total;
    private long comment;
    private long like;
    private long follow;
    private long system;
}
