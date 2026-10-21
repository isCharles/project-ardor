package com.projectardor.assistant.proposal.web;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record DeletionSnapshotRequest(@NotNull @Size(max = 5000) List<@NotNull UUID> ids) {}
