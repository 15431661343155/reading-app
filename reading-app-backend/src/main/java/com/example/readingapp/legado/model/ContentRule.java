package com.example.readingapp.legado.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContentRule {

    private String content;

    private String nextContentUrl;

    private String webJs;

    private String sourceRegex;

    private String replaceRegex;
}
