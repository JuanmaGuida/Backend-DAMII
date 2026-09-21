package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.LabelResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LabelService {
    private final LabelRepository labelRepository;
    private final TicketLabelRepository ticketLabelRepository;
    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final Clock clock;

    @Transactional
    public LabelResponse create(CreateLabelRequest request) {
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (labelRepository.existsByCodeIgnoreCase(code)) throw new LabelConflictException("Ya existe una etiqueta con ese código");
        Label label = new Label();
        label.setCode(code);
        label.setName(request.getName().trim());
        label.setDescription(trimNullable(request.getDescription()));
        label.setActive(true);
        label.setCreatedAt(clock.instant()); label.setUpdatedAt(clock.instant());
        return toResponse(labelRepository.saveAndFlush(label));
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> list() {
        return labelRepository.findAllWithTicketCount().stream().map(this::toResponse).toList();
    }


    @Transactional(readOnly = true)
    public LabelResponse get(UUID id) { return toResponse(find(id)); }

    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(UUID id, Pageable pageable) {
        find(id);
        return ticketService.listTickets(new TicketFilter(null, null, null, null, null, Set.of(id)), pageable);
    }


    @Transactional
    public LabelResponse update(UUID id, UpdateLabelRequest request) {
        Label label = find(id);
        label.setName(request.getName().trim()); label.setDescription(trimNullable(request.getDescription()));
        label.setActive(request.getActive()); label.setUpdatedAt(clock.instant());
        return toResponse(labelRepository.save(label));
    }

    @Transactional
    public void delete(UUID id) {
        Label label = find(id);
        if (ticketLabelRepository.existsById_LabelId(id)) throw new LabelConflictException("La etiqueta está asignada y no puede eliminarse");
        labelRepository.delete(label);
    }

    @Transactional
    public void assign(UUID ticketId, AssignLabelsRequest request, AuthenticatedIdentity actor) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        ticketService.requireTriageAuthority(ticket, actor);
        List<Label> labels = labelRepository.findAllById(request.getLabelIds());
        if (labels.size() != request.getLabelIds().size()) throw new ResourceNotFoundException("Una o más etiquetas no existen");
        if (labels.stream().anyMatch(label -> !label.isActive())) throw new LabelConflictException("No se pueden asignar etiquetas inactivas");
        for (Label label : labels) ticketLabelRepository.insertManualIfAbsent(ticketId, label.getId(), actor.citizenId(), clock.instant());
    }

    @Transactional
    public void remove(UUID ticketId, UUID labelId, AuthenticatedIdentity actor) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        ticketService.requireTriageAuthority(ticket, actor);
        ticketLabelRepository.deleteAssignment(ticketId, labelId);
    }

    private Label find(UUID id) { return labelRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("La etiqueta no existe")); }
    private LabelResponse toResponse(Label l) {
        return new LabelResponse(l.getId(), l.getCode(), l.getName(), l.getDescription(), l.isActive(), 0);
    }
    private LabelResponse toResponse(LabelWithTicketCount l) {
        return new LabelResponse(l.getId(), l.getCode(), l.getName(), l.getDescription(), l.getActive(), l.getTicketCount());
    }
    private String trimNullable(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}