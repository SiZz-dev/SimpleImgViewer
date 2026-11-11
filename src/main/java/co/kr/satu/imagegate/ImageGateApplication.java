package co.kr.satu.imagegate;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ImageGateProperties.class)
public class ImageGateApplication {

    public static void main(String[] args) {
        if(java.util.Arrays.stream(args).anyMatch(a->a.equals("--maintenance")||a.startsWith("--maintenance="))){
            if(!args[0].equals("--maintenance")){System.err.println("INVALID_OPTIONS: --maintenance must be the first argument");System.exit(2);return;}
            int status=co.kr.satu.imagegate.maintenance.controller.MaintenanceCommand.run(
                java.util.Arrays.copyOfRange(args,1,args.length),System.out,System.err);
            System.exit(status);return;
        }
        SpringApplication.run(ImageGateApplication.class, args);
    }
}
