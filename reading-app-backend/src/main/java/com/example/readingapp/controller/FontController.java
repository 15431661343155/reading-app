package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Font;
import com.example.readingapp.repository.FontRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fonts")
@CrossOrigin(origins = "*")
public class FontController {

    @Autowired
    private FontRepository fontRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Font>>> getFonts() {
        List<Font> fonts = fontRepository.findAllByOrderBySortOrderAsc();
        return ResponseEntity.ok(ApiResponse.success(fonts));
    }
}
