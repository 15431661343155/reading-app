package com.example.readingapp.legado.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchRule {

    private String checkKeyWord;

    /** 列表选择器 */
    private String bookList;

    private String name;

    private String author;

    private String intro;

    /** 分类 */
    private String kind;

    private String lastChapter;

    private String updateTime;

    private String coverUrl;

    private String wordCount;

    private String bookUrl;
}
