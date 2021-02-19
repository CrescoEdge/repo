package io.cresco.repo;

import com.google.gson.Gson;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

public class ExecutorImpl implements Executor {

    private PluginBuilder plugin;
    private CLogger logger;
    private Gson gson;
    private AtomicBoolean lockRepo = new AtomicBoolean();


    public ExecutorImpl(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(), CLogger.Level.Info);
        gson = new Gson();

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
            logger.error("repoList error" + ex.toString());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            logger.error(pw.toString());
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
            ex.printStackTrace();
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
            ex.printStackTrace();
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
                        }
                    }
                }
            }


        } catch(Exception ex){
            ex.printStackTrace();
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
            ex.printStackTrace();
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
            ex.printStackTrace();
        }
        return incoming;
    }


}