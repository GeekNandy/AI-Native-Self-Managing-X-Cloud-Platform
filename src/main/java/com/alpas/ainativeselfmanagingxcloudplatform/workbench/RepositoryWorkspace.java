package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/** Managed source snapshots only. Never clones arbitrary URLs or executes repository scripts. */
@Service
public class RepositoryWorkspace {
    private final Path root;
    private final StateStore store;
    private final CommandRunner commands;
    private final Clock clock;
    private final Map<String,Repository> repositories = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    public RepositoryWorkspace(StateStore store, CommandRunner commands, Clock clock) {
        this.store = store; this.commands = commands; this.clock = clock;
        try { root = store.directory() == null ? Files.createTempDirectory("xcloud-repositories-") : store.directory().resolve("repositories"); Files.createDirectories(root); }
        catch (Exception e) { throw new IllegalStateException("Cannot create managed repository directory.", e); }
        store.all("repositories", Repository.class).forEach(r -> repositories.put(r.id(), r));
    }
    public static void validateFiles(Map<String,String> files, String testClass) {
        if (files == null || files.size() < 2 || files.size() > 24 || testClass == null || !testClass.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,8}"))
            throw new PlatformException(400, "INVALID_REPOSITORY", "Provide 2-24 Java source/test files and a test main class.");
        int total = 0; boolean main = false, test = false;
        for (var entry : files.entrySet()) {
            String path = entry.getKey(), content = entry.getValue();
            if (path == null || path.length() > 100 || !path.matches("src/(main|test)/java/([A-Za-z][A-Za-z0-9_]*/)*[A-Za-z][A-Za-z0-9_]*\\.java") || content == null || content.length() > 32_768 || content.indexOf('\0') >= 0)
                throw new PlatformException(400, "INVALID_REPOSITORY", "Only bounded UTF-8 Java files under src/main/java and src/test/java are accepted.");
            total += content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            main |= path.startsWith("src/main/"); test |= path.startsWith("src/test/");
        }
        if (total > 200_000 || !main || !test || !files.containsKey("src/test/java/" + testClass.replace('.', '/') + ".java"))
            throw new PlatformException(400, "INVALID_REPOSITORY", "A source file and matching test entrypoint are required within the 200 KB budget.");
    }
    public synchronized Repository create(String name, Map<String,String> files, String testClass, String origin) {
        validateFiles(files, testClass);
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,59}")) throw new PlatformException(400, "INVALID_REPOSITORY", "Use a short repository display name.");
        if (repositories.size() >= 25) throw new PlatformException(429, "REPOSITORY_CAPACITY", "At most 25 managed repositories are retained.");
        String id = UUID.randomUUID().toString(); Path dir = root.resolve(id);
        writeFiles(dir, files);
        commands.require(dir, "git", "init", "--initial-branch=main");
        commands.require(dir, "git", "add", "--", "src");
        commands.require(dir, "git", "-c", "core.hooksPath=/dev/null", "-c", "user.name=X Cloud Operator", "-c", "user.email=operator@localhost", "commit", "--no-gpg-sign", "-m", "Import bounded Java source snapshot");
        String head = commands.require(dir, "git", "rev-parse", "HEAD");
        var repository = new Repository(id, name, testClass, head, origin, clock.instant());
        store.put("repositories", id, repository); repositories.put(id, repository); return repository;
    }
    public Repository require(String id) {
        var r = repositories.get(id); if (r == null) throw new PlatformException(404, "REPOSITORY_NOT_FOUND", "Managed repository not found."); return r;
    }
    public Repository current(String id) { var r = require(id); return new Repository(r.id(), r.name(), r.testClass(), head(id), r.origin(), r.createdAt()); }
    public List<Repository> list() { return repositories.values().stream().map(r -> current(r.id())).sorted(Comparator.comparing(Repository::createdAt).reversed()).toList(); }
    public Object lock(String id) { require(id); return locks.computeIfAbsent(id, unused -> new Object()); }
    public String head(String id) { require(id); return commands.require(root.resolve(id), "git", "rev-parse", "HEAD"); }
    public Path worktree(String id, String run, String base) {
        require(id);
        if (!run.matches("[a-z0-9-]{1,100}") || !base.matches("[a-f0-9]{40,64}")) throw new IllegalArgumentException("Unsafe managed worktree coordinates");
        Path path = root.resolve("worktrees").resolve(run);
        commands.require(root.resolve(id), "git", "-c", "core.hooksPath=/dev/null", "worktree", "add", "--detach", path.toString(), base);
        return path;
    }
    public void discard(String id, Path path) {
        commands.require(root.resolve(id), "git", "worktree", "remove", "--force", path.toString());
    }
    public Map<String,String> sources(Path path) {
        var result = new TreeMap<String,String>();
        try (var files = Files.walk(path.resolve("src"))) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalStateException("Symlink in managed source");
                result.put(path.relativize(file).toString().replace('\\','/'), Files.readString(file));
            }
        } catch (Exception e) { throw new PlatformException(409, "REPOSITORY_CONFLICT", "Cannot read the managed source snapshot."); }
        return result;
    }
    public static void writeFiles(Path root, Map<String,String> files) {
        try { for (var entry : files.entrySet()) { Path path = root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue()); } }
        catch (Exception e) { throw new PlatformException(503, "WORKTREE_UNAVAILABLE", "Cannot write the disposable worktree."); }
    }
    public String diff(Path path) { return commands.require(path, "git", "diff", "--no-ext-diff", "--", "src/main/java"); }
    public void discardOrphan(String id,String run) {
        for(String name:List.of(run,"approval-"+run)) {
            Path path=root.resolve("worktrees").resolve(name);
            if(Files.exists(path)) try { discard(id,path); } catch(RuntimeException ignored) {}
        }
    }
    public boolean matchesApprovedCommit(String id,String base,String run,String digest) {
        try {
            Path path=root.resolve(id);
            return commands.require(path,"git","rev-parse","HEAD^").equals(base)
                    && commands.require(path,"git","log","-1","--format=%B").equals("Apply reviewed repair "+run+"\n\nPlan-Digest: "+digest);
        } catch(RuntimeException failure) { return false; }
    }
    public String commit(String id, Path worktree, String base, String run, String digest) {
        if (!head(id).equals(base)) throw new PlatformException(409, "STALE_PATCH", "The repository head changed. Run and review a fresh repair.");
        commands.require(worktree, "git", "add", "--", "src/main/java");
        commands.require(worktree, "git", "-c", "core.hooksPath=/dev/null", "-c", "user.name=X Cloud Operator", "-c", "user.email=operator@localhost", "commit", "--no-gpg-sign", "-m", "Apply reviewed repair " + run + "\n\nPlan-Digest: " + digest);
        String commit = commands.require(worktree, "git", "rev-parse", "HEAD");
        // Compare-and-swap the managed branch. The root checkout is never used for execution.
        commands.require(root.resolve(id), "git", "update-ref", "refs/heads/main", commit, base);
        commands.require(root.resolve(id), "git", "reset", "--hard", commit);
        var r = require(id); var updated = new Repository(r.id(),r.name(),r.testClass(),commit,r.origin(),r.createdAt());
        store.put("repositories", id, updated); repositories.put(id, updated); return commit;
    }
}
