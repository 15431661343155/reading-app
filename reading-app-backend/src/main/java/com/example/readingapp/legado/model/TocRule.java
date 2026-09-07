package com.example.readingapp.legado.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TocRule {

    private String chapterList;

    private String chapterName;

    private String chapterUrl;

    private String isVolume;

    private String isVip;

    private String isPay;

    private String updateTime;

    private String nextTocUrl;
}
