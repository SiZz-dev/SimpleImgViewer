package co.kr.satu.imagegate.viewer.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/**
 * Untrusted viewer-open input. Authorization and metadata-dependent page/time
 * checks must run in the application service after this structural validation.
 */
public record ViewerSessionRequest(
        @NotEmpty @Size(max = 100)
        List<@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,127}") String> mediaIds,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,127}") String initialMediaId,
        @Min(1) Integer initialPage,
        @PositiveOrZero Double initialTimeSeconds,
        @Pattern(regexp = "dark|light") String theme,
        Boolean leftPanelOpen) {

    public ViewerSessionRequest {
        if (mediaIds != null) {
            // Preserve null elements for Bean Validation rather than throwing
            // NullPointerException before a structured validation response.
            mediaIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(mediaIds));
        }
        theme = theme == null ? "dark" : theme;
        leftPanelOpen = leftPanelOpen == null ? Boolean.TRUE : leftPanelOpen;
    }

    @AssertTrue(message = "mediaIds must not contain duplicates")
    public boolean isMediaSelectionUnique() {
        return mediaIds == null || new HashSet<>(mediaIds).size() == mediaIds.size();
    }

    @AssertTrue(message = "initialMediaId must belong to mediaIds")
    public boolean isInitialMediaSelected() {
        return initialMediaId == null || mediaIds == null || mediaIds.contains(initialMediaId);
    }

    @AssertTrue(message = "initialTimeSeconds must be finite")
    public boolean isInitialTimeFinite() {
        return initialTimeSeconds == null || Double.isFinite(initialTimeSeconds);
    }

    public String selectedMediaId() {
        if (initialMediaId != null) {
            return initialMediaId;
        }
        return mediaIds == null || mediaIds.isEmpty() ? null : mediaIds.getFirst();
    }
}
