package co.kr.satu.imagegate.conversion.exception;
import java.io.IOException;
public final class TileAdmissionException extends IOException {
    public enum Code {CAPACITY,SPACE,LIMIT,CLOSED}
    private final Code code;
    public TileAdmissionException(Code code){super("Tile admission rejected");this.code=code;}
    public Code code(){return code;}
}
