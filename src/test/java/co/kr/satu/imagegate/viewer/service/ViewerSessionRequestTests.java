package co.kr.satu.imagegate.viewer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.viewer.controller.dto.ViewerSessionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class ViewerSessionRequestTests {
    static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @Test
    void minimumRequestDefaultsToDarkAndFirstMedia() {
        var request = request(List.of("img-001"), null, null, null);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.theme()).isEqualTo("dark");
        assertThat(request.leftPanelOpen()).isTrue();
        assertThat(request.selectedMediaId()).isEqualTo("img-001");
    }

    @Test
    void rejectsMissingEmptyOrOversizedSelection() {
        assertThat(VALIDATOR.validate(request(null, null, null, null))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(List.of(), null, null, null))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(Collections.nCopies(101, "img-001"), null, null, null)))
                .isNotEmpty();
    }

    @Test
    void rejectsPathsUrlsNullAndOversizedIds() {
        for (String id : Arrays.asList("../secret", "D:\\media\\one.tif",
                "https://host/file", "", "a".repeat(129), null)) {
            assertThat(VALIDATOR.validate(request(Arrays.asList(id), null, null, null))).isNotEmpty();
        }
    }

    @Test
    void rejectsDuplicatesAndInitialMediaOutsideSelection() {
        assertThat(VALIDATOR.validate(request(List.of("a", "a"), null, null, null))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(List.of("a"), "other", null, null))).isNotEmpty();
    }

    @Test
    void rejectsInvalidThemePageAndNonFiniteTimes() {
        assertThat(VALIDATOR.validate(new ViewerSessionRequest(List.of("a"), null, null,
                null, "system", true))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(List.of("a"), null, 0, null))).isNotEmpty();
        for (double time : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThat(VALIDATOR.validate(request(List.of("a"), null, null, time))).isNotEmpty();
        }
    }

    @Test
    void preventsMutationOfValidatedSelection() {
        var ids = new ArrayList<>(List.of("a"));
        var request = request(ids, null, null, null);
        ids.add("other");
        assertThat(request.mediaIds()).containsExactly("a");
    }

    @Test
    void checksImagePageAgainstVerifiedMetadata() {
        MediaPositionValidator.validate(request(List.of("a"), null, 3, null), MediaType.IMAGE, 3, null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                MediaPositionValidator.validate(request(List.of("a"), null, 4, null),
                        MediaType.IMAGE, 3, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MediaPositionValidator.validate(request(List.of("a"), null, null, 0.0),
                        MediaType.IMAGE, 3, null));
    }

    @Test
    void checksPlaybackRangeForBothVideoAndAudio() {
        for (var type : List.of(MediaType.VIDEO, MediaType.AUDIO)) {
            MediaPositionValidator.validate(request(List.of("a"), null, null, 2.5), type, null, 10.0);
            assertThatIllegalArgumentException().isThrownBy(() ->
                    MediaPositionValidator.validate(request(List.of("a"), null, null, 10.0),
                            type, null, 10.0));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    MediaPositionValidator.validate(request(List.of("a"), null, 1, null),
                            type, null, 10.0));
        }
    }

    private ViewerSessionRequest request(List<String> ids, String selected, Integer page, Double time) {
        return new ViewerSessionRequest(ids, selected, page, time, null, null);
    }
}
