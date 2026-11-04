package io.cresco.repo;

import com.google.gson.Gson;
import io.cresco.library.capability.*;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.metrics.CMetric;
import io.cresco.library.metrics.MeasurementEngine;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

@CrescoCapabilities(namespace = "repo", target = "plugin",
        routingParams = {"region", "agent", "pluginid"},
        summary = "Plugin repository: lists/serves/stores plugin JARs for the fabric, plus repo inventory metrics.")
@CrescoActions({
    @CrescoAction(name = "repolist",
        summary = "List every plugin JAR in this repo (name, md5, jar file) plus this repo's server identity.",
        why = "Use to discover which plugins are available to deploy and their md5 for integrity-checked pulls.",
        returns = @CrescoReturn(name = "repolist", type = "object", compressed = true, description = "compressed JSON: {plugins:[{pluginname,md5,jarfile}], server:[{region,agent,pluginid}]}")),
    @CrescoAction(name = "getjar",
        summary = "Retrieve a plugin JAR's bytes by plugin name + md5.",
        why = "Use to fetch a specific plugin artifact for deployment or caching.",
        params = {
            @CrescoParam(name = "action_pluginname", required = true, description = "plugin symbolic name, e.g. io.cresco.sysinfo"),
            @CrescoParam(name = "action_pluginmd5", required = true, description = "content hash of the desired jar (integrity + version select)")
        },
        returns = @CrescoReturn(name = "jardata", type = "binary", description = "raw jar bytes (present only if found)")),
    @CrescoAction(name = "putjar", roles = {"infra", "admin"},
        summary = "Store a plugin JAR in this repo. Accepted only if it is signed by a signer that chains to " +
                  "plugin_signing_truststore; the repo computes its SHA-384 and content hash itself.",
        why = "Use to publish/replicate a plugin artifact into a repo instance.",
        params = {
            @CrescoParam(name = "pluginname", required = true, description = "plugin symbolic name: must match the jar's Bundle-SymbolicName"),
            @CrescoParam(name = "md5", description = "advisory content hash: a mismatch is refused, a match proves nothing"),
            @CrescoParam(name = "jarfile", required = true, description = "target jar file name: letters, digits, '.', '_', '-'"),
            @CrescoParam(name = "version", description = "plugin version"),
            @CrescoParam(name = "jardata", type = "binary", required = true, description = "raw jar bytes")
        },
        returns = {
            @CrescoReturn(name = "uploaded", type = "boolean", description = "the plugin name, if stored"),
            @CrescoReturn(name = "md5-confirm", description = "content hash of the stored jar, computed here"),
            @CrescoReturn(name = "sha384", description = "SHA-384 of the stored jar, computed here"),
            @CrescoReturn(name = "status", description = "10 stored; 9 refused (see refusal); 8 bad name; 7 missing params")
        }),
    @CrescoAction(name = "getmetrics",
        summary = "Return repo inventory metrics (plugin count, total bytes on disk) as MeasurementEngine gauges JSON.",
        why = "Standard cross-bundle metrics contract; folded into getmetricinventory.",
        returns = @CrescoReturn(name = "metrics", type = "object", description = "getAllMetrics() JSON")),
    @CrescoAction(name = "getcapabilities",
        summary = "Return this plugin's self-describing capability document (its message actions as LLM tool specs).",
        why = "Discovery: lets a client/LLM learn what this plugin can do and how to call it.",
        returns = @CrescoReturn(name = "capabilities", type = "object", description = "CapabilityDocument JSON"))
})
public class ExecutorImpl implements Executor {

    /** Verifies a jar that arrived over the network; returns its SHA-384 (lowercase hex) or throws to refuse it. */
    interface JarVerifier {
        String verify(Path jar) throws Exception;
    }

    static final String STAGING_PREFIX = ".repo-";
    static final String STAGING_SUFFIX = ".part";
    private static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,254}");

    private PluginBuilder plugin;
    private CLogger logger;
    private Gson gson;
    // one landing moves into place at a time; reads never wait for it (a move is atomic)
    private final Object lockRepo = new Object();
    private final JarInventory inventory;
    private volatile JarVerifier jarVerifier;

    // B-2 unified metrics: repository inventory size / on-disk footprint as MeasurementEngine gauges.
    private MeasurementEngine metricEngine;

    public ExecutorImpl(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(), CLogger.Level.Info);
        gson = new Gson();
        inventory = new JarInventory(plugin);
        // the agent's plugin-signing policy: JarSignatures against plugin_signing_truststore; no trust store, no jar
        jarVerifier = jar -> plugin.verifyJarSignature(jar);
    }

    /** Tests: the jar check. */
    void setJarVerifier(JarVerifier verifier) {
        this.jarVerifier = verifier;
    }

    static boolean isStaging(String name) {
        return name != null && name.startsWith(STAGING_PREFIX) && name.endsWith(STAGING_SUFFIX);
    }

    /** Cheap plugin-jar count for the central HealthCheck (no metric engine dependency). */
    public int getRepoPluginCount() {
        try {
            File repoDir = getRepoDir();
            if (repoDir != null) return inventory.list(repoDir).size();
        } catch (Exception ex) {
            logger.error("getRepoPluginCount() " + ex.getMessage(), ex);
        }
        return 0;
    }

    private synchronized String getMetricsJson() {
        try {
            if (metricEngine == null) {
                metricEngine = new MeasurementEngine(plugin);
                metricEngine.setGauge("repo.plugin.count", "plugin jars in the local repository", "repo", CMetric.MeasureClass.GAUGE_INT);
                metricEngine.setGauge("repo.bytes", "total size of repository jars on disk", "repo", CMetric.MeasureClass.GAUGE_LONG);
            }
            int count = 0;
            long bytes = 0L;
            File repoDir = getRepoDir();
            if (repoDir != null) {
                for (JarInventory.Entry e : inventory.list(repoDir)) {
                    count++;
                    bytes += e.size;
                }
            }
            metricEngine.updateIntGauge("repo.plugin.count", count);
            metricEngine.updateLongGauge("repo.bytes", bytes);
            return gson.toJson(metricEngine.getAllMetrics());
        } catch (Exception ex) {
            logger.error("getMetricsJson() " + ex.getMessage(), ex);
            return "{}";
        }
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeDISCOVER(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeERROR(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeINFO(MsgEvent incoming) { return null; }
    @Override
    public MsgEvent executeEXEC(MsgEvent incoming) {

        // the keys only: the values can carry a whole jar
        logger.debug("Processing Exec message : " + incoming.getParam("action") + " " + incoming.getParams().keySet());

        if(incoming.getParams().containsKey("action")) {
            switch (incoming.getParam("action")) {

                case "repolist":
                    return repoList(incoming);
                case "getjar":
                    return getPluginJar(incoming);
                case "putjar":
                    return putPluginJar(incoming);
                case "getmetrics":
                    // B-2 unified metrics: repository inventory gauges
                    incoming.setParam("metrics", getMetricsJson());
                    incoming.setParam("status", "10");
                    return incoming;
                case "getcapabilities":
                    return CapabilityResponder.respond(incoming, this);
            }
        }
        return null;

    }
    @Override
    public MsgEvent executeWATCHDOG(MsgEvent incoming) { return null;}
    @Override
    public MsgEvent executeKPI(MsgEvent incoming) { return null; }

    private MsgEvent repoList(MsgEvent msg) {

        try {
            Map<String, List<Map<String, String>>> repoMap = new HashMap<>();
            List<Map<String, String>> pluginInventory = new ArrayList<>();
            File repoDir = getRepoDir();
            if (repoDir != null) {
                for (JarInventory.Entry e : inventory.list(repoDir)) pluginInventory.add(e.asMap());
            }

            repoMap.put("plugins", pluginInventory.isEmpty() ? null : pluginInventory);

            List<Map<String, String>> repoInfo = getRepoInfo();
            repoMap.put("server", repoInfo);
            msg.setCompressedParam("repolist", gson.toJson(repoMap));
        }catch (Exception ex) {
            logger.error("repoList error", ex);
        }

        return msg;

    }

    private List<Map<String,String>> getRepoInfo() {
        List<Map<String,String>> repoInfo = null;
        try {
            repoInfo = new ArrayList<>();
            Map<String, String> repoMap = new HashMap<>();
            repoMap.put("region",plugin.getRegion());
            repoMap.put("agent",plugin.getAgent());
            repoMap.put("pluginid",plugin.getPluginID());
            repoInfo.add(repoMap);
        } catch(Exception ex) {
            logger.error("getRepoInfo error", ex);
        }
        return repoInfo;
    }

    File getRepoDir() {
        File repoDir = null;
        try {

            String repoDirString = plugin.getPluginDataDirectory() + "/" + plugin.getConfig().getStringParam("repo_dir", "repo");
            //make all directories
            Paths.get(repoDirString).toFile().mkdirs();

            File tmpRepo = new File(repoDirString);
            if(tmpRepo.isDirectory()) {
                repoDir = tmpRepo;
            } else {
                tmpRepo.mkdir();
                repoDir = tmpRepo;
            }

        } catch(Exception ex) {
            logger.error("getRepoDir error", ex);
        }
        return repoDir;
    }

    /**
     * putjar (FR-D3): the jar is staged beside the repository under a name the inventory never lists, accepted only if
     * it is signed by a signer that chains to plugin_signing_truststore (the repo computes its SHA-384 and content hash
     * itself; a hash the caller supplies is advisory, and one that differs is refused), and moved into place
     * atomically. Other agents load these jars as bundles: an unsigned or foreign-signed one never lands.
     */
    private MsgEvent putPluginJar(MsgEvent incoming) {

        Path staged = null;
        try {
            String pluginName = incoming.getParam("pluginname");
            String pluginMD5 = incoming.getParam("md5");
            String pluginJarFile = incoming.getParam("jarfile");
            byte[] jarData = incoming.getDataParam("jardata");
            if (incoming.getParams().containsKey("jardata")) incoming.removeParam("jardata");

            if (pluginName == null || pluginJarFile == null || jarData == null) {
                logger.error("putPluginJar: Missing data!");
                incoming.setParam("status", "7");
                incoming.setParam("status_desc", "missing required putjar params (pluginname, jarfile, jardata)");
                return incoming;
            }
            // CONTAINMENT: the jar file name comes off the wire; legit names are simple basenames
            if (!JAR_NAME.matcher(pluginJarFile).matches() || pluginJarFile.contains("..") || isStaging(pluginJarFile)) {
                logger.error("putPluginJar: rejecting unsafe jarfile name: [" + pluginJarFile + "]");
                incoming.setParam("status", "8");
                incoming.setParam("status_desc", "invalid jarfile name");
                return incoming;
            }
            File repoDir = getRepoDir();
            if (repoDir == null) {
                incoming.setParam("status", "6");
                incoming.setParam("status_desc", "no repository directory");
                return incoming;
            }
            Path target = repoDir.toPath().resolve(pluginJarFile);
            staged = target.resolveSibling(STAGING_PREFIX + UUID.randomUUID().toString().replace("-", "") + STAGING_SUFFIX);
            Files.write(staged, jarData, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            jarData = null;

            String sha384;
            try {
                sha384 = jarVerifier.verify(staged);
            } catch (Exception refused) {
                logger.warn("putjar " + pluginJarFile + " refused: " + refused.getMessage());
                incoming.setParam("status", "9");
                incoming.setParam("status_desc", "jar refused: " + refused.getMessage());
                incoming.setParam("refusal", "signature");
                return incoming;
            }
            String contentHash = plugin.getMD5(staged.toString());
            if (contentHash == null) {
                incoming.setParam("status", "6");
                incoming.setParam("status_desc", "the staged jar could not be read back");
                return incoming;
            }
            if (pluginMD5 != null && !pluginMD5.equals(contentHash)) {
                logger.error("putjar: incoming md5 " + pluginMD5 + " differs from the computed " + contentHash);
                incoming.setParam("status", "9");
                incoming.setParam("status_desc", "the jar's content hash differs from the md5 supplied, upload rejected");
                return incoming;
            }
            String declared = symbolicName(staged);
            if (declared != null && !declared.equals(pluginName)) {
                incoming.setParam("status", "9");
                incoming.setParam("status_desc", "pluginname does not match the jar's Bundle-SymbolicName");
                return incoming;
            }
            synchronized (lockRepo) {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            staged = null;
            incoming.setParam("uploaded", pluginName);
            incoming.setParam("md5-confirm", contentHash);
            incoming.setParam("sha384", sha384);
            incoming.setParam("status", "10");
            incoming.setParam("status_desc", "uploaded");

        } catch(Exception ex){
            logger.error("putPluginJar error", ex);
            incoming.setParam("status", "6");
            incoming.setParam("status_desc", "putjar error: " + ex.getClass().getSimpleName());
        } finally {
            // a partial, refused or unverified jar is never left where the inventory would list and serve it
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (Exception ignore) { }
            }
        }

        if(incoming.getParams().containsKey("jardata")) {
            incoming.removeParam("jardata");
        }

        return incoming;
    }

    /** The jar's Bundle-SymbolicName without directives, or null. */
    private static String symbolicName(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            if (jf.getManifest() == null) return null;
            String v = jf.getManifest().getMainAttributes().getValue("Bundle-SymbolicName");
            if (v == null) return null;
            int semi = v.indexOf(';');
            return (semi < 0 ? v : v.substring(0, semi)).trim();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * getjar: the inventory is cached by size, mtime and file key (it used to re-hash every jar on every call); the
     * bytes sent are rechecked against the requested hash, so a jar changed in place is never served under its old hash.
     */
    private MsgEvent getPluginJar(MsgEvent incoming) {

        try {
            String requestPluginName = incoming.getParam("action_pluginname");
            String requestPluginMD5 = incoming.getParam("action_pluginmd5");
            if (requestPluginName == null || requestPluginMD5 == null) {
                incoming.setParam("status", "8");
                incoming.setParam("status_desc", "getjar takes action_pluginname and action_pluginmd5");
                return incoming;
            }
            File repoDir = getRepoDir();
            if (repoDir != null) {
                for (JarInventory.Entry e : inventory.list(repoDir)) {
                    if (!requestPluginName.equals(e.pluginname) || !requestPluginMD5.equals(e.md5)) continue;
                    Path jarPath = repoDir.toPath().resolve(e.jarfile);
                    byte[] data = Files.readAllBytes(jarPath);
                    if (!requestPluginMD5.equals(contentHash(data))) {
                        inventory.invalidate(jarPath);
                        incoming.setParam("status", "9");
                        incoming.setParam("status_desc", "the jar changed since it was listed; list again");
                        return incoming;
                    }
                    incoming.setDataParam("jardata", data);
                    incoming.setParam("status", "10");
                    return incoming;
                }
            }
            incoming.setParam("status", "9");
            incoming.setParam("status_desc", "no such jar");
        } catch(Exception ex) {
            logger.error("getPluginJar error", ex);
            incoming.setParam("status", "7");
            incoming.setParam("status_desc", "getjar error: " + ex.getClass().getSimpleName());
        }
        return incoming;
    }

    /** The library's content hash (what getMD5 and the inventory report) of bytes in memory. */
    static String contentHash(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance(io.cresco.library.crypto.CrescoCrypto.CONTENT_HASH_ALG);
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

}
