package co.kr.satu.imagegate.maintenance.util;
import java.nio.channels.FileChannel;
import java.nio.file.*;
/** Test-only separate JVM holding a known empty marker until stdin closes. */
public final class OriginalLockProcessFixture {
    public static void main(String[] args) throws Exception {
        try(var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.WRITE);var lock=channel.lock()){
            System.out.println("LOCKED");System.out.flush();System.in.read();
        }
    }
}
