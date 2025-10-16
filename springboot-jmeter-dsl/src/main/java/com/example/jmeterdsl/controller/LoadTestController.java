package com.example.jmeterdsl.controller;

import com.example.jmeterdsl.dto.LoadTestRequest;
import com.example.jmeterdsl.dto.LoadTestResponse;
import com.example.jmeterdsl.service.LoadTestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/load-test")
public class LoadTestController {

    private final LoadTestService loadTestService;

    public LoadTestController(LoadTestService loadTestService) {
        this.loadTestService = loadTestService;
    }

    @PostMapping("/run")
    public ResponseEntity<LoadTestResponse> run(@Valid @RequestBody LoadTestRequest request) {
        return ResponseEntity.ok(loadTestService.runTest(request));
    }
}
