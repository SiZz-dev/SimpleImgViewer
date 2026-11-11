package co.kr.satu.imagegate.conversion.domain;
/** Internal snapshot; public DTO mapping must omit keys, hashes and module profiles. */
public record TileView(TileJobStatus status,TileCacheManifest manifest) {}
