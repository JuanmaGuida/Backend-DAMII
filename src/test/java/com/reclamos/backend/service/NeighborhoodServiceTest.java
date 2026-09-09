package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.NeighborhoodResponse;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.NeighborhoodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NeighborhoodServiceTest {
    @Mock
    private NeighborhoodRepository neighborhoodRepository;

    @Test
    void mapsAndSortsEveryNeighborhoodUsingNaturalArgentineSpanishOrderWithoutWriting() {
        when(neighborhoodRepository.findAll()).thenReturn(List.of(
                neighborhood("Villa Devoto"),
                neighborhood("Versalles"),
                neighborhood("Villa Urquiza"),
                neighborhood("Núñez"),
                neighborhood("Villa Ortúzar"),
                neighborhood("Vélez Sarsfield"),
                neighborhood("Villa del Parque"),
                neighborhood("Agronomía"),
                neighborhood("Villa Crespo")));

        List<NeighborhoodResponse> result = new NeighborhoodService(neighborhoodRepository).findAll();

        assertEquals(9, result.size());
        assertEquals("Agronomía", result.getFirst().getName());
        assertEquals(1000, result.getFirst().getPopulation());
        assertEquals(List.of("Agronomía", "Núñez", "Vélez Sarsfield", "Versalles", "Villa Crespo",
                        "Villa del Parque", "Villa Devoto", "Villa Ortúzar", "Villa Urquiza"),
                result.stream().map(NeighborhoodResponse::getName).toList());
        verify(neighborhoodRepository).findAll();
        verifyNoMoreInteractions(neighborhoodRepository);
    }

    @Test
    void emptyRepositoryResultReturnsEmptyListWithoutWriting() {
        when(neighborhoodRepository.findAll()).thenReturn(List.of());

        List<NeighborhoodResponse> result = new NeighborhoodService(neighborhoodRepository).findAll();

        assertTrue(result.isEmpty());
        verify(neighborhoodRepository).findAll();
        verifyNoMoreInteractions(neighborhoodRepository);
    }

    @Test
    void existingIdReturnsMappedResponseWithoutWriting() {
        UUID id = UUID.fromString("0c02dffa-8a9c-5007-bce1-2b46669dc7d8");
        when(neighborhoodRepository.findById(id))
                .thenReturn(Optional.of(neighborhood(id, "Agronomía", 13912)));

        NeighborhoodResponse result = new NeighborhoodService(neighborhoodRepository).findById(id);

        assertEquals(id, result.getId());
        assertEquals("Agronomía", result.getName());
        assertEquals(13912, result.getPopulation());
        verify(neighborhoodRepository).findById(id);
        verifyNoMoreInteractions(neighborhoodRepository);
    }

    @Test
    void missingIdThrowsControlledNotFoundWithoutWriting() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(neighborhoodRepository.findById(id)).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> new NeighborhoodService(neighborhoodRepository).findById(id));

        assertEquals("No se encontró el barrio solicitado.", exception.getMessage());
        verify(neighborhoodRepository).findById(id);
        verifyNoMoreInteractions(neighborhoodRepository);
    }

    private Neighborhood neighborhood(String name) {
        return neighborhood(UUID.randomUUID(), name, 1000);
    }

    private Neighborhood neighborhood(UUID id, String name, int population) {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(id);
        neighborhood.setName(name);
        neighborhood.setPopulation(population);
        return neighborhood;
    }
}