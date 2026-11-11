package co.kr.satu.imagegate.media.domain;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;

public record MediaPage(MediaRecord media,PreviewStatus preview) {}
