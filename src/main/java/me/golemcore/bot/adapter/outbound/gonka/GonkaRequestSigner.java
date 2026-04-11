package me.golemcore.bot.adapter.outbound.gonka;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;

public class GonkaRequestSigner {

    private static final String GONKA_ADDRESS_PREFIX = "gonka";
    private static final String SECP256K1_CURVE = "secp256k1";
    private static final char[] BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l".toCharArray();
    private static final int BECH32_GENERATOR_0 = 0x3b6a57b2;
    private static final int BECH32_GENERATOR_1 = 0x26508e6d;
    private static final int BECH32_GENERATOR_2 = 0x1ea119fa;
    private static final int BECH32_GENERATOR_3 = 0x3d4233dd;
    private static final int BECH32_GENERATOR_4 = 0x2a1462b3;
    private static final int BECH32_CHECKSUM_LENGTH = 6;
    private static final int SIGNATURE_PART_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;
    private static final BigInteger HALF_CURVE_ORDER;
    private static final X9ECParameters CURVE_PARAMETERS;
    private static final ECDomainParameters DOMAIN_PARAMETERS;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CURVE_PARAMETERS = CustomNamedCurves.getByName(SECP256K1_CURVE);
        DOMAIN_PARAMETERS = new ECDomainParameters(
                CURVE_PARAMETERS.getCurve(),
                CURVE_PARAMETERS.getG(),
                CURVE_PARAMETERS.getN(),
                CURVE_PARAMETERS.getH());
        HALF_CURVE_ORDER = CURVE_PARAMETERS.getN().shiftRight(1);
    }

    private final Clock clock;
    private final AtomicLong lastTimestampNanos = new AtomicLong();

    public GonkaRequestSigner() {
        this(Clock.systemUTC());
    }

    GonkaRequestSigner(Clock clock) {
        this.clock = clock;
    }

    public SignedRequest sign(String payload, String privateKeyHex, String requesterAddress, String transferAddress) {
        if (isBlank(privateKeyHex)) {
            throw new IllegalArgumentException("Gonka private key is required");
        }
        if (isBlank(transferAddress)) {
            throw new IllegalArgumentException("Gonka transferAddress is required");
        }
        String resolvedAddress = !isBlank(requesterAddress) ? requesterAddress.trim() : deriveAddress(privateKeyHex);
        long timestamp = currentTimestampNanos();
        String signature = createSignature(payload != null ? payload : "", privateKeyHex, timestamp,
                transferAddress.trim());
        return new SignedRequest(signature, resolvedAddress, Long.toString(timestamp));
    }

    String createSignature(String payload, String privateKeyHex, long timestamp, String transferAddress) {
        byte[] payloadHash = sha256(payload.getBytes(StandardCharsets.UTF_8));
        String payloadHashHex = HexFormat.of().formatHex(payloadHash);
        byte[] signatureInput = (payloadHashHex + timestamp + transferAddress).getBytes(StandardCharsets.UTF_8);
        byte[] signatureHash = sha256(signatureInput);
        BigInteger privateKey = new BigInteger(1, parsePrivateKey(privateKeyHex));
        ECDSASigner signer = new ECDSASigner(
                new HMacDSAKCalculator(new org.bouncycastle.crypto.digests.SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, DOMAIN_PARAMETERS));
        BigInteger[] signature = signer.generateSignature(signatureHash);
        BigInteger r = signature[0];
        BigInteger s = normalizeLowS(signature[1]);
        byte[] rawSignature = new byte[SIGNATURE_LENGTH];
        copyPart(toFixedLength(r), rawSignature, 0);
        copyPart(toFixedLength(s), rawSignature, SIGNATURE_PART_LENGTH);
        return java.util.Base64.getEncoder().encodeToString(rawSignature);
    }

    String deriveAddress(String privateKeyHex) {
        BigInteger privateKey = new BigInteger(1, parsePrivateKey(privateKeyHex));
        ECPoint publicKey = CURVE_PARAMETERS.getG().multiply(privateKey).normalize();
        byte[] compressedPublicKey = publicKey.getEncoded(true);
        byte[] shaHash = sha256(compressedPublicKey);
        byte[] ripemdHash = ripemd160(shaHash);
        return encodeBech32(GONKA_ADDRESS_PREFIX, convertBits(ripemdHash, 8, 5, true));
    }

    private long currentTimestampNanos() {
        long wallClockNanos = Math.addExact(
                Math.multiplyExact(clock.instant().getEpochSecond(), 1_000_000_000L),
                clock.instant().getNano());
        return lastTimestampNanos.updateAndGet(previous -> wallClockNanos > previous ? wallClockNanos : previous + 1);
    }

    private BigInteger normalizeLowS(BigInteger s) {
        return s.compareTo(HALF_CURVE_ORDER) > 0 ? CURVE_PARAMETERS.getN().subtract(s) : s;
    }

    private byte[] parsePrivateKey(String privateKeyHex) {
        String clean = privateKeyHex.trim();
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if (clean.length() != SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("Gonka private key must be a 32-byte hex value");
        }
        return HexFormat.of().parseHex(clean);
    }

    private byte[] toFixedLength(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == SIGNATURE_PART_LENGTH) {
            return bytes;
        }
        byte[] fixed = new byte[SIGNATURE_PART_LENGTH];
        if (bytes.length > SIGNATURE_PART_LENGTH) {
            System.arraycopy(bytes, bytes.length - SIGNATURE_PART_LENGTH, fixed, 0, SIGNATURE_PART_LENGTH);
        } else {
            System.arraycopy(bytes, 0, fixed, SIGNATURE_PART_LENGTH - bytes.length, bytes.length);
        }
        return fixed;
    }

    private void copyPart(byte[] source, byte[] target, int offset) {
        System.arraycopy(source, 0, target, offset, SIGNATURE_PART_LENGTH);
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private byte[] ripemd160(byte[] data) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    private byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxValue = (1 << toBits) - 1;
        byte[] output = new byte[(data.length * fromBits + toBits - 1) / toBits];
        int outputIndex = 0;
        for (byte datum : data) {
            int value = datum & 0xff;
            if ((value >>> fromBits) != 0) {
                throw new IllegalArgumentException("Invalid bech32 input value");
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                output[outputIndex] = (byte) ((acc >> bits) & maxValue);
                outputIndex++;
            }
        }
        if (pad && bits > 0) {
            output[outputIndex] = (byte) ((acc << (toBits - bits)) & maxValue);
            outputIndex++;
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxValue) != 0) {
            throw new IllegalArgumentException("Invalid bech32 padding");
        }
        return Arrays.copyOf(output, outputIndex);
    }

    private String encodeBech32(String humanReadablePart, byte[] data) {
        byte[] checksum = createChecksum(humanReadablePart, data);
        StringBuilder builder = new StringBuilder(humanReadablePart.length() + 1 + data.length + checksum.length);
        builder.append(humanReadablePart).append('1');
        appendBech32Data(builder, data);
        appendBech32Data(builder, checksum);
        return builder.toString();
    }

    private byte[] createChecksum(String humanReadablePart, byte[] data) {
        byte[] values = new byte[hrpExpand(humanReadablePart).length + data.length + BECH32_CHECKSUM_LENGTH];
        byte[] expanded = hrpExpand(humanReadablePart);
        System.arraycopy(expanded, 0, values, 0, expanded.length);
        System.arraycopy(data, 0, values, expanded.length, data.length);
        int polymod = polymod(values) ^ 1;
        byte[] checksum = new byte[BECH32_CHECKSUM_LENGTH];
        for (int index = 0; index < BECH32_CHECKSUM_LENGTH; index++) {
            checksum[index] = (byte) ((polymod >> (5 * (BECH32_CHECKSUM_LENGTH - 1 - index))) & 31);
        }
        return checksum;
    }

    private byte[] hrpExpand(String humanReadablePart) {
        byte[] expanded = new byte[humanReadablePart.length() * 2 + 1];
        int index = 0;
        for (int position = 0; position < humanReadablePart.length(); position++) {
            expanded[index] = (byte) (humanReadablePart.charAt(position) >> 5);
            index++;
        }
        expanded[index] = 0;
        index++;
        for (int position = 0; position < humanReadablePart.length(); position++) {
            expanded[index] = (byte) (humanReadablePart.charAt(position) & 31);
            index++;
        }
        return expanded;
    }

    private int polymod(byte[] values) {
        int checksum = 1;
        for (byte value : values) {
            int top = checksum >>> 25;
            checksum = ((checksum & 0x1ffffff) << 5) ^ (value & 0xff);
            checksum = applyGenerator(checksum, top, 0, BECH32_GENERATOR_0);
            checksum = applyGenerator(checksum, top, 1, BECH32_GENERATOR_1);
            checksum = applyGenerator(checksum, top, 2, BECH32_GENERATOR_2);
            checksum = applyGenerator(checksum, top, 3, BECH32_GENERATOR_3);
            checksum = applyGenerator(checksum, top, 4, BECH32_GENERATOR_4);
        }
        return checksum;
    }

    private int applyGenerator(int checksum, int top, int bit, int generator) {
        return ((top >> bit) & 1) == 1 ? checksum ^ generator : checksum;
    }

    private void appendBech32Data(StringBuilder builder, byte[] data) {
        for (byte value : data) {
            builder.append(BECH32_CHARSET[value]);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SignedRequest(String authorization, String requesterAddress, String timestamp) {
    }
}
