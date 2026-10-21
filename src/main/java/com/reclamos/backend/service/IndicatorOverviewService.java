package com.reclamos.backend.service;

import com.reclamos.backend.dto.IndicatorOverviewFilter;
import com.reclamos.backend.dto.response.IndicatorOverviewResponse;
import com.reclamos.backend.repository.IndicatorOverviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.io.OutputStream;

@Service
@RequiredArgsConstructor
public class IndicatorOverviewService {
    private final IndicatorOverviewRepository repository;
    private final Clock clock;

    /** All three bounded queries see one database snapshot, even during concurrent transitions. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IndicatorOverviewResponse overview(IndicatorOverviewFilter filter) {
        filter.validate();
        return repository.overview(filter, clock.instant());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void export(IndicatorOverviewFilter filter, OutputStream output) {
        filter.validate();
        repository.export(filter, clock.instant(), output);
    }
}
