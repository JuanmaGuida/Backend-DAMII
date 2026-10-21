package com.reclamos.backend.controller;

import com.reclamos.backend.dto.IndicatorOverviewFilter;
import com.reclamos.backend.dto.response.IndicatorOverviewResponse;
import com.reclamos.backend.service.IndicatorOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/api/indicators")
@RequiredArgsConstructor
public class IndicatorOverviewController {
    private final IndicatorOverviewService service;

    @GetMapping("/overview")
    public ResponseEntity<IndicatorOverviewResponse> overview(@ModelAttribute IndicatorOverviewFilter filter) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(filter));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public void export(@ModelAttribute IndicatorOverviewFilter filter, HttpServletResponse response) throws IOException {
        filter.validate(); // Reject invalid requests before committing any CSV headers or bytes.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=metricas.csv");
        response.setContentType("text/csv;charset=UTF-8");
        // Keep the authenticated request and cursor transaction on the same thread.
        service.export(filter, response.getOutputStream());
    }
}
