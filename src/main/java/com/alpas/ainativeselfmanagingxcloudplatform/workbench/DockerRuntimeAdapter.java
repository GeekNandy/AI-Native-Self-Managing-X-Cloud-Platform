package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Local development adapter. Capabilities are checked independently from logical cloud policy. */
@Component
@ConditionalOnProperty(name="platform.runtime",havingValue="docker")
public class DockerRuntimeAdapter implements RuntimeAdapter {
    private final CommandRunner commands; private final IntentCompiler compiler; private final StateStore store;
    private final JsonMapper json; private final String image, owner;
    private final Map<String,Snapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    public record Snapshot(String id,IntentSpec intent,boolean unavailable) {}
    public record Owner(String id) {}
    public DockerRuntimeAdapter(CommandRunner commands,IntentCompiler compiler,StateStore store,JsonMapper json,
            @Value("${platform.sandbox.image:eclipse-temurin:21-jdk}") String image) {
        this.commands=commands;this.compiler=compiler;this.store=store;this.json=json;this.image=image;
        var owners=store.all("docker-owner",Owner.class); owner=owners.isEmpty()?UUID.randomUUID().toString():owners.getFirst().id();
        store.put("docker-owner","instance",new Owner(owner)); store.all("docker-runtime",Snapshot.class).forEach(s -> snapshots.put(s.id(),s));
    }
    @Override public String mode() { return "docker-local"; }
    @Override public List<PolicyCheck> capabilityChecks(IntentSpec s) {
        return List.of(new PolicyCheck("DOCKER_SCOPE","tier",s.tier().equals("development"),"Local Docker supports development environments only."),
                new PolicyCheck("DOCKER_REGION","region",s.region().equals("local"),"Choose local; this adapter cannot enforce a cloud residency region."),
                new PolicyCheck("DOCKER_TOPOLOGY","zones",s.zones()==1 && s.replicas()<=3,"One local zone and at most three replicas are supported."),
                new PolicyCheck("DOCKER_NETWORK","restrictedEgress",s.privateNetwork() && s.restrictedEgress(),"The local workload uses network=none, with no published ports or egress."),
                new PolicyCheck("DOCKER_DATA","dataClassification",!s.dataClassification().equals("pii") && !s.encryptedStorage(),"Ephemeral public/internal workloads only; encrypted storage and PII are unsupported."),
                new PolicyCheck("DOCKER_ROLLBACK","rollback",!s.rollback(),"Automatic rollback is unsupported; review a fresh plan to restore an earlier configuration."));
    }
    @Override public IntentSpec apply(String id,IntentSpec target) {
        if(capabilityChecks(target).stream().anyMatch(c->!c.passed())) throw new PlatformException(409,"CAPABILITY_DENIED","The local runtime cannot enforce this intent.");
        var snapshot=new Snapshot(id,target,false); store.put("docker-runtime",id,snapshot); snapshots.put(id,snapshot);
        for(String container:containers(id)) run("rm","--force",container);
        String main=compiler.artifacts(target).stream().filter(a->a.path().equals("Main.java")).findFirst().orElseThrow().content();
        var files=Map.of("Main.java",main,"Health.java","import java.net.*; public class Health { public static void main(String[] a) throws Exception { var c=(HttpURLConnection)new URL(\"http://127.0.0.1:8080/health\").openConnection(); c.setConnectTimeout(1000); c.setReadTimeout(1000); if(c.getResponseCode()!=200) System.exit(1); System.out.println(\"healthy\"); } }");
        for(int index=0;index<target.replicas();index++) {
            String name="xcloud-runtime-"+owner.substring(0,8)+"-"+IntentCompiler.digest(id).substring(0,10)+"-"+index;
            run("create","--pull=never","--name",name,"--label","xcloud.owner="+owner,"--label","xcloud.environment="+id,
                    "--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--cpus=0.5","--memory=256m","--memory-swap=256m","--pids-limit=64",
                    "--tmpfs","/workspace:rw,nosuid,nodev,noexec,size=2m,mode=755","--tmpfs","/tmp:rw,nosuid,nodev,size=32m,mode=1777","--log-driver=none",image,"sleep","86400");
            run("start",name);
            require(commands.run(null,Duration.ofSeconds(5),List.of("docker","exec","-i",name,"tar","--no-same-owner","-xf","-","-C","/workspace"),DockerSandbox.tar(files)));
            run("exec",name,"sh","-c","mkdir /tmp/classes && javac -J-Xmx128m -proc:none --release 21 -d /tmp/classes /workspace/Main.java /workspace/Health.java");
            run("exec","--detach","--user","10001:10001",name,"java","-Xmx128m","-XX:ActiveProcessorCount=1","-cp","/tmp/classes","Main");
            boolean healthy=false;
            for(int attempt=0;attempt<8;attempt++) {
                var probe=commands.run(null,Duration.ofSeconds(3),List.of("docker","exec","--user","10001:10001",name,"java","-Xmx64m","-cp","/tmp/classes","Health"));
                if(probe.exitCode()==0) { healthy=true; break; }
            }
            if(!healthy) throw new PlatformException(502,"RUNTIME_NOT_HEALTHY","The generated Java workload did not become healthy.");
        }
        return observe(id);
    }
    @Override public IntentSpec observe(String id) {
        var s=snapshots.get(id); if(s==null || s.unavailable()) return null;
        int healthy=0; boolean restricted=true,privateNetwork=true;
        try {
            for(String container:containers(id)) {
                var state=json.readTree(run("inspect",container)).path(0);
                restricted &= state.path("HostConfig").path("NetworkMode").asText().equals("none");
                privateNetwork &= state.path("HostConfig").path("PortBindings").isEmpty();
                if(!state.path("State").path("Running").asBoolean()) continue;
                var probe=commands.run(null,Duration.ofSeconds(3),List.of("docker","exec","--user","10001:10001",container,"java","-Xmx64m","-cp","/tmp/classes","Health"));
                if(probe.exitCode()==0) healthy++;
            }
        } catch(RuntimeException unavailable) { return null; }
        var t=s.intent(); return new IntentSpec(t.serviceName(),t.runtime(),t.tier(),t.region(),privateNetwork,restricted,t.dataClassification(),t.encryptedStorage(),t.zones(),healthy,t.monitoring(),t.rollback());
    }
    @Override public void inject(String id,String kind) {
        var s=snapshots.get(id); if(s==null) throw new PlatformException(409,"NOT_PROVISIONED","Provision the local runtime first.");
        boolean unavailable=s.unavailable();
        switch(kind) {
            case "replica-loss" -> { var containers=containers(id); if(!containers.isEmpty()) run("rm","--force",containers.getFirst()); }
            case "observation-lost" -> unavailable=true;
            case "observation-restored" -> unavailable=false;
            default -> throw new PlatformException(400,"FAULT_UNSUPPORTED","Docker mode supports replica-loss and observation-lost/restored.");
        }
        var updated=new Snapshot(id,s.intent(),unavailable); store.put("docker-runtime",id,updated); snapshots.put(id,updated);
    }
    private List<String> containers(String id) { String ids=run("ps","--all","--quiet","--filter","label=xcloud.owner="+owner,"--filter","label=xcloud.environment="+id); return ids.isBlank()?List.of():List.of(ids.split("\\s+")); }
    private String run(String... args) { var command=new ArrayList<String>();command.add("docker");command.addAll(List.of(args)); return require(commands.run(null,Duration.ofSeconds(15),command)); }
    private String require(CommandRunner.Result result) { if(result.exitCode()!=0 || result.truncated()) throw new PlatformException(502,"DOCKER_RUNTIME_FAILED","The local Docker operation failed. Check the daemon and pre-pulled image; refresh observations before retrying."); return result.output().strip(); }
    public void dispose(String id) { for(String container:containers(id)) run("rm","--force",container); }
}
