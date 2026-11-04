package io.cresco.repo;

import io.cresco.library.messaging.MsgEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review (review/plugins-20261104) of repo putjar (3b27fb0). Fails on that commit. Local only: never pushed.
 */
class ReviewAdversarialTest extends PutJarTest {

    /**
     * LOW-MED (race, simulated with the verifier hook). The jar is written to a staging file in the repository
     * directory and then re-read BY PATH by JarSignatures.verify (three passes), getMD5, symbolicName and the move:
     * nothing pins the verified bytes. Bytes that replace the staged file after verification land, and putjar reports
     * the SHA-384 of the bytes it verified, not of the bytes it stored. The bytes are already in memory (jardata):
     * hash and verify those, from a private file nobody else can rename over.
     */
    @Test
    void putjarStoresExactlyTheBytesItVerified() throws Exception {
        byte[] good = JarSigning.signedJar("io.cresco.example", "v1");
        byte[] evil = JarSigning.unsignedJar("io.cresco.example", "evil");
        exec.setJarVerifier(p -> {
            String h = plugin.verifyJarSignature(p);
            Path other = Files.write(p.resolveSibling("swap.tmp"), evil);
            Files.move(other, p, StandardCopyOption.REPLACE_EXISTING);
            return h;
        });
        MsgEvent r = putjar("io.cresco.example", "example.jar", good);
        if ("10".equals(r.getParam("status"))) {
            byte[] stored = Files.readAllBytes(exec.getRepoDir().toPath().resolve("example.jar"));
            assertEquals(r.getParam("sha384"), sha384(stored),
                    "the stored jar is not the one whose signature was verified and whose SHA-384 was reported");
        }
    }
}
