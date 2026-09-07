package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.OperationLog;
import com.example.readingapp.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OperationLogController {
    
    private final OperationLogService operationLogService;
    
    // 获取最近的操作日志
    @GetMapping("/recent")
    public ApiResponse<List<OperationLog>> getRecentLogs(@RequestParam(defaultValue = "50") int limit) {
        List<OperationLog> logs = operationLogService.getRecentLogs(Math.min(limit, 100));
        return ApiResponse.success(logs);
    }
}
