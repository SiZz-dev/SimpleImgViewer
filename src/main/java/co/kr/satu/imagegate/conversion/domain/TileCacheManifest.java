package co.kr.satu.imagegate.conversion.domain;

import java.util.List;

/** Internal immutable normalized generation; does not grant file access. */
public record TileCacheManifest(int schemaVersion,String policy,String key,String generation,
        String sourceManifestSha256,int page,TilePyramidLayout layout,long totalBytes,List<TilePyramidManifest.Tile> tiles) {
    public TileCacheManifest { tiles=List.copyOf(tiles); }
}
