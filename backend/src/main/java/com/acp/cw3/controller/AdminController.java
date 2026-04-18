package com.acp.cw3.controller;

import com.acp.cw3.service.IngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final IngestionService ingestionService;

    public AdminController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh() {
        String source = ingestionService.refresh();
        return Map.of("status", "ok", "sourceType", source);
    }
}
