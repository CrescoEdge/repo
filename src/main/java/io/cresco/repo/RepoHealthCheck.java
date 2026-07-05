package io.cresco.repo;

import io.cresco.library.plugin.PluginBuilder;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;

/**
 * Central health for the plugin repository. Registered as an {@code org.apache.felix.hc.api.HealthCheck}
 * OSGi service (name "repo", tag "local") so the controller's CrescoHealthExecutor discovers and
 * schedules it alongside the built-in checks — the same Felix Health Check system every other Cresco
 * bundle uses. Reports the plugin-jar count; self-guards while the plugin is still coming up.
 */
public class RepoHealthCheck implements HealthCheck {

    private final PluginBuilder plugin;
    private final ExecutorImpl executor;

    public RepoHealthCheck(PluginBuilder plugin, ExecutorImpl executor) {
        this.plugin = plugin;
        this.executor = executor;
    }

    @Override
    public Result execute() {
        try {
            if (plugin == null || !plugin.isActive() || executor == null) {
                return new Result(Result.Status.TEMPORARILY_UNAVAILABLE, "repo not active");
            }
            int jars = executor.getRepoPluginCount();
            return new Result(Result.Status.OK, "repo OK: " + jars + " plugin jar(s)");
        } catch (Exception ex) {
            return new Result(Result.Status.WARN, "repo health error: " + ex.getMessage());
        }
    }
}
