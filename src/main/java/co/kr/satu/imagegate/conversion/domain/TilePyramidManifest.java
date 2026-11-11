package co.kr.satu.imagegate.conversion.domain;

import java.util.List;

/** Internal, read-only validation result. Native PNGs still require sanitation before publication. */
public record TilePyramidManifest(int schemaVersion, String profile, String moduleIdentity,
        int page, TilePyramidLayout layout, String coordinateSpace, String descriptorSha256,
        long totalBytes, List<Tile> tiles) {
    public TilePyramidManifest { tiles = List.copyOf(tiles); }
    public record Tile(int level, int column, int row, int width, int height, long bytes, String sha256) {}
}
