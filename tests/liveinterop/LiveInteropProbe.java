import com.klywo.hermeswatch.livecall.LiveContract;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEST ONLY cross-language probe. Drives the real {@link LiveContract} against a
 * challenge body produced by the actual Python broker core and prints the Java
 * canonical message, the Java HMAC and the parsed integer expiry. No network,
 * no provider, no Android runtime; {@code LiveContract} is the production class.
 */
public final class LiveInteropProbe {
    public static void main(String[] args) throws Exception {
        String hex = args[0];
        byte[] key = new byte[LiveContract.KEY_BYTES];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] body = Files.readAllBytes(Paths.get(args[1]));
        LiveContract.Challenge challenge = LiveContract.Challenge.parse(body);
        System.out.println(challenge.canonicalMessage());
        System.out.println(LiveContract.mac(key, challenge.canonicalMessage()));
        System.out.println(challenge.expiresAt);
    }

    private LiveInteropProbe() { }
}
