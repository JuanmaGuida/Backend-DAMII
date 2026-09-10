package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.NeighborhoodResponse;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.NeighborhoodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NeighborhoodService {
    private final NeighborhoodRepository neighborhoodRepository;

    public List<NeighborhoodResponse> findAll() {
        Collator collator = Collator.getInstance(Locale.of("es", "AR"));
        return neighborhoodRepository.findAll().stream()
                .sorted((left, right) -> collator.compare(left.getName(), right.getName()))
                .map(this::toResponse)
                .toList();
    }

    public NeighborhoodResponse findById(UUID id) {
        return neighborhoodRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró el barrio solicitado."));
    }

    private NeighborhoodResponse toResponse(Neighborhood neighborhood) {
        NeighborhoodResponse response = new NeighborhoodResponse();
        response.setId(neighborhood.getId());
        response.setName(neighborhood.getName());
        response.setPopulation(neighborhood.getPopulation());
        return response;
    }
}