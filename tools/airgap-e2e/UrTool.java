import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sparrowwallet.hummingbird.ResultType;
import com.sparrowwallet.hummingbird.UR;
import com.sparrowwallet.hummingbird.URDecoder;
import com.sparrowwallet.hummingbird.UREncoder;
import com.sparrowwallet.hummingbird.registry.CryptoAccount;
import com.sparrowwallet.hummingbird.registry.CryptoHDKey;
import com.sparrowwallet.hummingbird.registry.CryptoPSBT;
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent;
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Host side of the air-gapped end-to-end test (see README.md). Reads the phone's animated
 * QR from screenshots and writes desktop PSBTs as UR parts for the debug adb hook.
 *
 *   decode [--testnet] <png>...   UR from screenshots: base64 PSBT, or a wpkh descriptor
 *                                 for a crypto-account. Exit 2 while incomplete.
 *   encode <base64-psbt>          ur:crypto-psbt parts, one per line
 *   selftest                      encode, render to PNG, decode, compare
 */
public class UrTool {
    private static final int MAINNET_XPUB = 0x0488B21E;
    private static final int TESTNET_XPUB = 0x043587CF;

    public static void main(String[] args) throws Exception {
        if(args.length == 0) usage();
        switch(args[0]) {
            case "decode" -> {
                boolean testnet = args.length > 1 && args[1].equals("--testnet");
                List<File> files = new ArrayList<>();
                for(int i = testnet ? 2 : 1; i < args.length; i++) files.add(new File(args[i]));
                String out = decode(files, testnet);
                if(out == null) System.exit(2);
                System.out.println(out);
            }
            case "encode" -> {
                if(args.length != 2) usage();
                for(String part : encode(Base64.getDecoder().decode(args[1].trim()))) System.out.println(part);
            }
            case "selftest" -> selftest();
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: UrTool decode [--testnet] <png>... | encode <base64-psbt> | selftest");
        System.exit(1);
    }

    static List<String> encode(byte[] psbt) throws Exception {
        UREncoder encoder = new UREncoder(new CryptoPSBT(psbt).toUR(), 250, 10, 0);
        List<String> parts = new ArrayList<>();
        if(encoder.isSinglePart()) {
            parts.add(encoder.nextPart());
            return parts;
        }
        // twice the minimum, so a few lost broadcasts still complete
        for(int i = 0; i < encoder.getSeqLen() * 2; i++) parts.add(encoder.nextPart());
        return parts;
    }

    /** Returns the decoded UR as text, or null (progress on stderr) while parts are missing. */
    static String decode(List<File> files, boolean testnet) throws Exception {
        URDecoder decoder = new URDecoder();
        int read = 0;
        for(File file : files) {
            String text = readQr(file);
            if(text == null) continue;
            read++;
            decoder.receivePart(text.toLowerCase());
            if(decoder.getResult() != null) break;
        }
        URDecoder.Result result = decoder.getResult();
        if(result == null) {
            System.err.printf("incomplete: %d QR frames read, %.0f%% of the UR%n", read,
                decoder.getEstimatedPercentComplete() * 100);
            return null;
        }
        if(result.type != ResultType.SUCCESS) throw new IllegalStateException("UR decode failed: " + result.error);
        Object item = result.ur.decodeFromRegistry();
        if(item instanceof CryptoPSBT psbt) return Base64.getEncoder().encodeToString(psbt.getPsbt());
        if(item instanceof CryptoAccount account) return descriptor(account, testnet ? TESTNET_XPUB : MAINNET_XPUB);
        throw new IllegalStateException("Unexpected UR type " + result.ur.getType());
    }

    private static String readQr(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        if(image == null) return null;
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            return new MultiFormatReader().decode(bitmap, Map.of(
                DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        } catch(NotFoundException e) {
            return null;
        }
    }

    /** Same rules as desktop QRScanDialog.getExtendedKey (and AirgapUr.desktopXpub on the phone). */
    static String descriptor(CryptoAccount account, int header) throws Exception {
        CryptoHDKey key = account.getOutputDescriptors().get(0).getHdKey();
        List<PathComponent> components = key.getOrigin().getComponents();
        IndexPathComponent last = (IndexPathComponent)components.get(components.size() - 1);
        long child = last.getIndex() + (last.isHardened() ? 0x80000000L : 0L);
        byte[] parent = key.getParentFingerprint() != null ? key.getParentFingerprint() : new byte[4];
        ByteArrayOutputStream xpub = new ByteArrayOutputStream();
        xpub.write(new byte[] {(byte)(header >>> 24), (byte)(header >>> 16), (byte)(header >>> 8), (byte)header});
        xpub.write(components.size());
        xpub.write(parent);
        xpub.write(new byte[] {(byte)(child >>> 24), (byte)(child >>> 16), (byte)(child >>> 8), (byte)child});
        xpub.write(key.getChainCode());
        xpub.write(key.getKey());
        String fingerprint = hex(account.getMasterFingerprint());
        return "wpkh([" + fingerprint + "/" + key.getOrigin().getPath() + "]" + base58Check(xpub.toByteArray()) + "/<0;1>/*)";
    }

    static void selftest() throws Exception {
        byte[] psbt = new byte[3000];
        new Random(42).nextBytes(psbt);
        System.arraycopy(new byte[] {0x70, 0x73, 0x62, 0x74, (byte)0xFF}, 0, psbt, 0, 5);
        List<String> parts = encode(psbt);
        Path dir = Files.createTempDirectory("urtool");
        List<File> pngs = new ArrayList<>();
        for(int i = 0; i < parts.size(); i++) {
            File png = dir.resolve("f" + i + ".png").toFile();
            MatrixToImageWriter.writeToPath(new QRCodeWriter().encode(parts.get(i).toUpperCase(), BarcodeFormat.QR_CODE,
                600, 600, Map.of(EncodeHintType.MARGIN, 2)), "png", png.toPath());
            pngs.add(png);
        }
        String decoded = decode(pngs, false);
        boolean ok = decoded != null && Arrays.equals(Base64.getDecoder().decode(decoded), psbt);
        System.out.println((ok ? "selftest ok: " : "selftest FAILED: ") + parts.size() + " parts via PNG");
        if(!ok) System.exit(1);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static String base58Check(byte[] payload) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] checksum = sha.digest(sha.digest(payload));
        byte[] data = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(checksum, 0, data, payload.length, 4);
        java.math.BigInteger n = new java.math.BigInteger(1, data);
        StringBuilder sb = new StringBuilder();
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        while(n.signum() > 0) {
            java.math.BigInteger[] qr = n.divideAndRemainder(base);
            sb.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for(byte b : data) {
            if(b != 0) break;
            sb.append('1');
        }
        return sb.reverse().toString();
    }
}
