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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
            @CrescoParam(name = "action_pluginmd5", required = true, description = "md5 of the desired jar (integrity + version select)")
        },
        returns = @CrescoReturn(name = "jardata", type = "binary", description = "raw jar bytes (present only if found)")),
    @CrescoAction(name = "putjar",
        summary = "Store a plugin JAR into this repo (writes to disk, verifies md5).",
        why = "Use to publish/replicate a plugin artifact into a repo instance.",
        params = {
            @CrescoParam(name = "pluginname", required = true, description = "plugin symbolic name"),
            @CrescoParam(name = "md5", required = true, description = "expected md5 of the jar"),
            @CrescoParam(name = "jarfile", required = true, description = "target jar file name"),
            @CrescoParam(name = "version", description = "plugin version"),
            @CrescoParam(name = "jardata", type = "binary", required = true, description = "raw jar bytes")
        },
        returns = {
            @CrescoReturn(name = "uploaded", type = "boolean", description = "true if stored"),
            @CrescoReturn(name = "md5-confirm", description = "md5 of the stored jar")
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

    private PluginBuilder plugin;
    private CLogger logger;
    private Gson gson;
    private AtomicBoolean lockRepo = new AtomicBoolean();

    // B-2 unified metrics: repository inventory size / on-disk footprint as MeasurementEngine gauges.
    private MeasurementEngine metricEngine;

    public ExecutorImpl(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(), CLogger.Level.Info);
        gson = new Gson();

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
                List<Map<String, String>> inv = plugin.getPluginInventory(repoDir.getAbsolutePath());
                if (inv != null) { count = inv.size(); }
                File[] files = repoDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
                if (files != null) { for (File f : files) { bytes += f.length(); } }
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


        logger.debug("Processing Exec message : " + incoming.getParams());

        if(incoming.getParams().containsKey("action")) {
            switch (incoming.getParam("action")) {

                case "repolist":
                    synchronized (lockRepo) {
                        return repoList(incoming);
                    }
                case "getjar":
                    synchronized (lockRepo) {
                        return getPluginJar(incoming);
                    }
                case "putjar":
                    synchronized (lockRepo) {
                        return putPluginJar(incoming);
                    }
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
            List<Map<String, String>> pluginInventory = null;
            File repoDir = getRepoDir();
            if (repoDir != null) {
                pluginInventory = plugin.getPluginInventory(repoDir.getAbsolutePath());
            }

            repoMap.put("plugins", pluginInventory);

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

    private File getRepoDir() {
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


    private MsgEvent putPluginJar(MsgEvent incoming) {

        try {


            String pluginName = incoming.getParam("pluginname");
            String pluginMD5 = incoming.getParam("md5");
            String pluginJarFile = incoming.getParam("jarfile");
            String pluginVersion = incoming.getParam("version");

            if((pluginName != null) && (pluginMD5 != null) && (pluginJarFile != null) && (pluginVersion != null)) {

                //File jarFile  = getPluginJarFile(pluginName);
                File repoDir = getRepoDir();
                if(repoDir != null) {
                    String jarFileSavePath = repoDir.getAbsolutePath() + "/" + pluginJarFile;
                    Path path = Paths.get(jarFileSavePath);
                    Files.write(path, incoming.getDataParam("jardata"));
                    File jarFileSaved = new File(jarFileSavePath);
                    if (jarFileSaved.isFile()) {
                        String md5 = plugin.getMD5(jarFileSavePath);
                        if (pluginMD5.equals(md5)) {
                            incoming.setParam("uploaded", pluginName);
                            incoming.setParam("md5-confirm", md5);
                        } else {
                            logger.error("MD5 hash not matched");
                            logger.error("incoming MD5: " + pluginMD5 + " calculated MD5: " + md5);
                        }
                    } else {
                        logger.error("putPluginJar !jarFileSaved.isFile()");
                    }
                } else {
                    logger.error("putPluginJar repoDir != null");
                }
            } else {
                logger.error("putPluginJar: Missing data!");
            }


        } catch(Exception ex){
            logger.error("putPluginJar error", ex);
        }

        if(incoming.getParams().containsKey("jardata")) {
            incoming.removeParam("jardata");
        }

        return incoming;
    }



    private File getPluginJarFile(String requestPluginName) {
        File jarFile = null;
        try {

                File repoDir = getRepoDir();
                if (repoDir != null) {

                    List<Map<String, String>> pluginInventory = plugin.getPluginInventory(repoDir.getAbsolutePath());
                    if(pluginInventory != null) {
                        for (Map<String, String> repoMap : pluginInventory) {

                            if (repoMap.containsKey("pluginname") && repoMap.containsKey("md5") && repoMap.containsKey("jarfile")) {
                                String pluginName = repoMap.get("pluginname");
                                String pluginMD5 = repoMap.get("md5");
                                String pluginJarFile = repoMap.get("jarfile");

                                if (pluginName.equals(requestPluginName)) {
                                    jarFile = new File(repoDir + "/" + pluginJarFile);
                                }
                            }
                        }
                    }
                }

        } catch(Exception ex) {
            logger.error("getPluginJarFile error", ex);
        }
        return jarFile;
    }

    private MsgEvent getPluginJar(MsgEvent incoming) {

        try {
            if ((incoming.getParam("action_pluginname") != null) && (incoming.getParam("action_pluginmd5") != null)) {
                String requestPluginName = incoming.getParam("action_pluginname");
                String requestPluginMD5 = incoming.getParam("action_pluginmd5");

                File repoDir = getRepoDir();
                if (repoDir != null) {

                    List<Map<String, String>> pluginInventory = plugin.getPluginInventory(repoDir.getAbsolutePath());
                    if(pluginInventory != null) {
                        for (Map<String, String> repoMap : pluginInventory) {

                            if (repoMap.containsKey("pluginname") && repoMap.containsKey("md5") && repoMap.containsKey("jarfile")) {
                                String pluginName = repoMap.get("pluginname");
                                String pluginMD5 = repoMap.get("md5");
                                String pluginJarFile = repoMap.get("jarfile");

                                if (pluginName.equals(requestPluginName) && pluginMD5.equals(requestPluginMD5)) {

                                    Path jarPath = Paths.get(repoDir + "/" + pluginJarFile);
                                    incoming.setDataParam("jardata", java.nio.file.Files.readAllBytes(jarPath));

                                }
                            }

                        }
                    }
                }
            }
        } catch(Exception ex) {
            logger.error("getPluginJar error", ex);
        }
        return incoming;
    }


}