package com.example.readingapp.legado.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookInfoRule {

    private String init;

    private String name;

    private String author;

    private String intro;

    private String kind;

    private String lastChapter;

    private String updateTime;

    private String coverUrl;

    private String wordCount;

    private String tocUrl;
}
