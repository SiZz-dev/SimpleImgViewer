package co.kr.satu.imagegate.media.service;

import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** No waiting queue; retains identities only while a registration is copying input. */
@Service
public final class UploadAdmission {
    private final ImageGateProperties.Upload policy;
    private final Map<ViewerPrincipal,Integer> active=new HashMap<>();
    private int total;
    @Autowired public UploadAdmission(ImageGateProperties properties){this(properties.upload());}
    public UploadAdmission(ImageGateProperties.Upload policy){this.policy=policy;}
    public synchronized Lease acquire(ViewerPrincipal actor){
        if(actor==null)throw new IllegalArgumentException("Trusted actor required");
        int count=active.getOrDefault(actor,0);
        if(total>=policy.maxConcurrent()||count>=policy.maxConcurrentPerUser())
            throw new MediaException(MediaException.Code.UPLOAD_BUSY);
        active.put(actor,count+1);total++;return new Lease(actor);
    }
    public final class Lease implements AutoCloseable {
        private final ViewerPrincipal actor;private boolean closed;
        private Lease(ViewerPrincipal actor){this.actor=actor;}
        @Override public void close(){synchronized(UploadAdmission.this){
            if(closed)return;closed=true;
            int count=active.get(actor);if(count==1)active.remove(actor);else active.put(actor,count-1);total--;
        }}
    }
}
