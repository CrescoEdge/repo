package io.cresco.repo;

import io.cresco.library.agent.AgentService;
import io.cresco.library.agent.AgentState;
import io.cresco.library.agent.ControllerState;
import io.cresco.library.capability.CrescoAction;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The repo plugin's putjar (FR-D3) and its role (item 1): only a jar signed by a signer that chains to the agent's
 * plugin_signing_truststore lands, checked by the library's JarSignatures; the repo computes the hashes; getjar serves
 * only bytes that match the requested hash, from an inventory that is not re-hashed on every call.
 */
class PutJarTest {

    @TempDir Path tmp;
    PluginBuilder plugin;
    ExecutorImpl exec;
    volatile boolean trustConfigured = true;
    volatile boolean signingRequired = true;

    @BeforeEach
    void up() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("pluginID", "plugin-0");
        plugin = new PluginBuilder(agent(), "io.cresco.repo.Plugin", null, cfg);
        plugin.setIsActive(true);
        exec = new ExecutorImpl(plugin);
    }

    @AfterEach
    void down() {
        plugin.setIsActive(false);
        plugin.close();
    }

    private AgentService agent() {
        ControllerState cs = (ControllerState) Proxy.newProxyInstance(ControllerState.class.getClassLoader(),
                new Class<?>[]{ControllerState.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRegion": return "region-1";
                        case "getAgent": return "agent-1";
                        case "isActive": return true;
                        default: return dflt(m.getReturnType());
                    }
                });
        AgentState state = new AgentState(cs);
        CLogger log = (CLogger) Proxy.newProxyInstance(CLogger.class.getClassLoader(), new Class<?>[]{CLogger.class},
                (p, m, a) -> null);
        return (AgentService) Proxy.newProxyInstance(AgentService.class.getClassLoader(), new Class<?>[]{AgentService.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getAgentState": return state;
                        case "getCLogger": return log;
                        case "getAgentDataDirectory": return tmp.resolve("agent").toString();
                        case "getPluginSigningTrust": return trustConfigured ? JarSigning.trustStore() : null;
                        case "isPluginSigningRequired": return signingRequired;
                        default: return dflt(m.getReturnType());
                    }
                });
    }

    static Object dflt(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        return null;
    }

    MsgEvent putjar(String name, String jarfile, byte[] jar, String... kv) {
        MsgEvent m = new MsgEvent(MsgEvent.Type.EXEC, "global", "global-controller", null, "region-1", "agent-1",
                "plugin-0", false, false);
        m.setParam("action", "putjar");
        m.setParam("pluginname", name);
        m.setParam("jarfile", jarfile);
        m.setParam("version", "1.3.0");
        m.setDataParam("jardata", jar);
        for (int i = 0; i + 1 < kv.length; i += 2) m.setParam(kv[i], kv[i + 1]);
        return exec.executeEXEC(m);
    }

    MsgEvent getjar(String name, String md5) {
        MsgEvent m = new MsgEvent(MsgEvent.Type.EXEC, "region-1", "agent-1", null, "region-1", "agent-1", "plugin-0", false, false);
        m.setParam("action", "getjar");
        m.setParam("action_pluginname", name);
        m.setParam("action_pluginmd5", md5);
        return exec.executeEXEC(m);
    }

    static String sha384(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-384").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    void assertNothingStaged() throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(exec.getRepoDir().toPath())) {
            assertTrue(s.noneMatch(p -> ExecutorImpl.isStaging(p.getFileName().toString())));
        }
    }

    @Test
    void putjarNeedsInfraOrAdmin() {
        Set<String> roles = null;
        for (CrescoAction a : ExecutorImpl.class.getAnnotationsByType(CrescoAction.class)) {
            if (a.name().equals("putjar")) roles = new HashSet<>(Arrays.asList(a.roles()));
            else assertEquals(0, a.roles().length, a.name() + " keeps the default");
        }
        assertEquals(Set.of("infra", "admin"), roles);
    }

    @Test
    void aSignedJarLandsWithTheHashesTheRepoComputed() throws Exception {
        byte[] jar = JarSigning.signedJar("io.cresco.example", "v1");
        String md5 = ExecutorImpl.contentHash(jar);
        // the global controller's saveToRepo: jarfile is the md5, and it checks md5-confirm against its own
        MsgEvent r = putjar("io.cresco.example", md5, jar, "md5", md5);
        assertEquals("10", r.getParam("status"), r.getParams().toString());
        assertEquals(md5, r.getParam("md5-confirm"));
        assertEquals(sha384(jar), r.getParam("sha384"));
        assertEquals("io.cresco.example", r.getParam("uploaded"));
        assertNull(r.getDataParam("jardata"));
        assertArrayEquals(jar, Files.readAllBytes(exec.getRepoDir().toPath().resolve(md5)));
        MsgEvent got = getjar("io.cresco.example", md5);
        assertArrayEquals(jar, got.getDataParam("jardata"));
        assertNothingStaged();
    }

    @Test
    void unsignedForeignTamperedAndMismatchedJarsNeverLand() throws Exception {
        assertEquals("signature", putjar("io.cresco.example", "u.jar", JarSigning.unsignedJar("io.cresco.example", "v1")).getParam("refusal"));
        assertEquals("signature", putjar("io.cresco.example", "f.jar", JarSigning.foreignSignedJar("io.cresco.example", "v1")).getParam("refusal"));
        assertEquals("signature", putjar("io.cresco.example", "t.jar", tamper(JarSigning.signedJar("io.cresco.example", "v1"))).getParam("refusal"));
        byte[] good = JarSigning.signedJar("io.cresco.example", "v1");
        assertEquals("9", putjar("io.cresco.example", "m.jar", good, "md5", "0000").getParam("status"), "a supplied hash that differs");
        assertEquals("9", putjar("io.cresco.other", "n.jar", good).getParam("status"), "a name that is not the jar's own");
        for (String name : new String[]{"u.jar", "f.jar", "t.jar", "m.jar", "n.jar"}) {
            assertFalse(Files.exists(exec.getRepoDir().toPath().resolve(name)), name);
        }
        for (String bad : new String[]{"../escape.jar", "a/b.jar", ".repo-x.part", "..", "/abs.jar"}) {
            assertEquals("8", putjar("io.cresco.example", bad, good).getParam("status"), bad);
        }
        assertNothingStaged();
        MsgEvent missing = putjar("io.cresco.example", null, good);
        assertEquals("7", missing.getParam("status"));
    }

    @Test
    void noTrustStoreMeansNoJarAndDevelopmentModeIsExplicit() throws Exception {
        trustConfigured = false;
        byte[] good = JarSigning.signedJar("io.cresco.example", "v1");
        assertEquals("signature", putjar("io.cresco.example", "x.jar", good).getParam("refusal"));
        signingRequired = false;
        byte[] unsigned = JarSigning.unsignedJar("io.cresco.example", "dev");
        MsgEvent dev = putjar("io.cresco.example", "dev.jar", unsigned);
        assertEquals("10", dev.getParam("status"), "plugin_signing_required=false, development only");
        assertEquals(sha384(unsigned), dev.getParam("sha384"));
    }

    @Test
    void theInventoryIsNotRehashedAndStaleBytesAreNeverServed() throws Exception {
        byte[] jar = JarSigning.signedJar("io.cresco.example", "v1");
        String md5 = ExecutorImpl.contentHash(jar);
        assertEquals("10", putjar("io.cresco.example", "e.jar", jar).getParam("status"));
        for (int i = 0; i < 3; i++) assertEquals("10", getjar("io.cresco.example", md5).getParam("status"));
        Path p = exec.getRepoDir().toPath().resolve("e.jar");
        FileTime mtime = Files.getLastModifiedTime(p);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[]{(byte) (jar[jar.length / 2] ^ 1)}), jar.length / 2);
        }
        Files.setLastModifiedTime(p, mtime);
        MsgEvent stale = getjar("io.cresco.example", md5);
        assertEquals("9", stale.getParam("status"));
        assertNull(stale.getDataParam("jardata"));
    }

    static byte[] tamper(byte[] jar) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(jar));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().endsWith("Payload.txt")) data = "tampered".getBytes();
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
