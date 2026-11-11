package co.kr.satu.imagegate.conversion.exception;
import java.io.IOException;
/** Absent/expired generation or coordinate; no raw storage diagnostics. */
public final class TileUnavailableException extends IOException {
    public TileUnavailableException(){super("Tile unavailable");}
}
