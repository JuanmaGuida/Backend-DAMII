package com.reclamos.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "attachment")
public class AttachmentProperties {
    @Valid
    private Storage storage = new Storage();

    @Valid
    private Limits limits = new Limits();

    @NotEmpty
    private Set<String> allowedContentTypes = new LinkedHashSet<>();

    @Getter
    @Setter
    public static class Storage {
        @NotNull
        private Path root;
    }

    @Getter
    @Setter
    public static class Limits {
        @Min(1)
        private int maxFiles;

        @NotNull
        private DataSize maxFileSize;

        @NotNull
        private DataSize maxTotalSize;
    }
}
