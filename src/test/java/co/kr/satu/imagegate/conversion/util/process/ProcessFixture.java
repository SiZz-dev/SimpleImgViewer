package co.kr.satu.imagegate.conversion.util.process;

import java.nio.file.Files;
import java.nio.file.Path;

public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> System.out.print(args[1]);
            case "fail" -> System.exit(7);
            case "spam" -> System.out.print("x".repeat(100_000));
            case "sleep" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(20_000);
            }
            case "environment" -> {
                var values=new java.util.Properties();
                System.getenv().forEach((key,value)->values.setProperty("env."+key,value));
                values.setProperty("property.inherited",System.getProperty("imagegate.fixture.inherited","absent"));
                values.setProperty("cwd",Path.of("").toRealPath().toString());
                values.setProperty("stdin",Integer.toString(System.in.read()));
                Files.writeString(Path.of("relative.txt"),"fixture output");
                values.store(System.out,"synthetic worker");
            }
            case "child-exit" -> {
                new ProcessBuilder(args[1],"-cp",args[2],ProcessFixture.class.getName(),"sleep",args[3]).start();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(!Files.exists(Path.of(args[3]))&&System.nanoTime()<deadline)Thread.sleep(10);
                Thread.sleep(500);System.out.print("parent-finished");
            }
            case "child" -> {
                var child = new ProcessBuilder(args[1], "-cp", args[2],
                        ProcessFixture.class.getName(), "sleep", args[3]).start();
                child.waitFor();
            }
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
    }
}
