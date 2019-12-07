package io.cresco.repo;


import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.*;

import java.util.Map;

@Component(
        service = { PluginService.class },
        scope=ServiceScope.PROTOTYPE,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        servicefactory = true,
        reference=@Reference(name="io.cresco.library.agent.AgentService", service= AgentService.class)
)

public class Plugin implements PluginService {

    public BundleContext context;
    private PluginBuilder pluginBuilder;
    private Executor executor;
    private CLogger logger;
    private Map<String,Object> map;

    @Activate
    void activate(BundleContext context, Map<String,Object> map) {

        this.context = context;
        this.map = map;

    }

    @Modified
    void modified(BundleContext context, Map<String,Object> map) {
        System.out.println("Modified Config Map PluginID:" + (String) map.get("pluginID"));
    }

    @Deactivate
    void deactivate(BundleContext context, Map<String,Object> map) {

        isStopped();
        this.context = null;
        this.map = null;

    }

    @Override
    public boolean isActive() {
        return pluginBuilder.isActive();
    }

    @Override
    public void setIsActive(boolean isActive) {
        pluginBuilder.setIsActive(isActive);
    }

    @Override
    public boolean inMsg(MsgEvent incoming) {
        pluginBuilder.msgIn(incoming);
        return true;
    }

    @Override
    public boolean isStarted() {

        try {
            if(pluginBuilder == null) {
                pluginBuilder = new PluginBuilder(this.getClass().getName(), context, map);
                this.logger = pluginBuilder.getLogger(Plugin.class.getName(), CLogger.Level.Info);
                this.executor = new ExecutorImpl(pluginBuilder);
                pluginBuilder.setExecutor(executor);

                while (!pluginBuilder.getAgentService().getAgentState().isActive()) {
                    logger.info("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                    Thread.sleep(1000);
                }

                //set plugin active
                pluginBuilder.setIsActive(true);
            }
            return true;
        } catch(Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isStopped() {

        if(pluginBuilder != null) {
            pluginBuilder.setExecutor(null);
            pluginBuilder.setIsActive(false);
        }
        return true;
    }



}