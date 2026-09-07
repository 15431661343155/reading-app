package com.example.readingapp.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ImportResult {
    private int total;
    private int success;
    private int failed;
    private int skipped;
    private List<String> messages = new ArrayList<>();

    public void addMessage(String msg) {
        messages.add(msg);
    }
}
