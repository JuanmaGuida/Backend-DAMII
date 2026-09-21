package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CreateLabelRequest;
import com.reclamos.backend.dto.request.UpdateLabelRequest;
import com.reclamos.backend.dto.response.LabelResponse;
import com.reclamos.backend.service.LabelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/staff/labels")
@RequiredArgsConstructor
public class StaffLabelController {

    private final LabelService labelService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LabelResponse create(@Valid @RequestBody CreateLabelRequest request) {
        return labelService.create(request);
    }

    @GetMapping
    public List<LabelResponse> list() {
        return labelService.list();
    }

    @GetMapping("/{labelId}")
    public LabelResponse get(@PathVariable("labelId") UUID labelId) {
        return labelService.get(labelId);
    }

    @GetMapping("/{labelId}/tickets")
    public Page<TicketResponse> listTickets(
            @PathVariable("labelId") UUID labelId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return labelService.listTickets(labelId, pageable);
    }


    @PutMapping("/{labelId}")
    public LabelResponse update(
            @PathVariable("labelId") UUID labelId,
            @Valid @RequestBody UpdateLabelRequest request) {

        return labelService.update(labelId, request);
    }

    @DeleteMapping("/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("labelId") UUID labelId) {
        labelService.delete(labelId);
    }
}