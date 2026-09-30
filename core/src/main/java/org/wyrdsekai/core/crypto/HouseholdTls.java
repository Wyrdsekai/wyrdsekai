package org.wyrdsekai.core.crypto;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The household's own certificate authority and this machine's certificate ( W2).
 *
 * <p>The CA is made once per home and kept; its SHA-256 fingerprint ({@code home_ca_fp}) rides in
 * every pairing invite, so a phone or another household machine can tell this home from anyone else
 * on the network. The leaf certificate carries this machine's names and LAN addresses; it serves
 * HTTPS/WSS on :7443 and TLS on the household bus (NATS :4222, websocket :4223). It is issued again
 * at boot when it is within {@link #RENEW_BEFORE} of expiry, when an address it should carry is
 * missing, or when it was not signed by the current CA. The CA key never leaves the data folder.</p>
 *
 * <p>Files, under {@code <data>/tls/}: the CA certificate (readable by all), the CA key (0600), the
 * leaf chain (leaf + CA) and its key (0600, or 0640 to the {@value #BUS_GROUP} group when that group
 * exists, so the packaged standalone NATS service can read it), and a PKCS#12 keystore for the web
 * server whose password is a random per-install secret in {@value #KEYSTORE_PASS} (0600).</p>
 */
public final class HouseholdTls {

    private static final Logger log = LoggerFactory.getLogger(HouseholdTls.class);

    public static final String DIR = "tls";
    public static final String CA_CERT = "household-ca.pem";
    public static final String CA_KEY = "household-ca.key";
    public static final String LEAF_CERT = "household-leaf.pem";
    public static final String LEAF_KEY = "household-leaf.key";
    public static final String KEYSTORE = "household.p12";
    public static final String KEYSTORE_PASS = "keystore.pass";
    public static final String KEYSTORE_ALIAS = "household";
    /** The group the packaged standalone nats-server runs under (deb); may read the leaf key. */
    public static final String BUS_GROUP = "wyrdsekai-nats";

    static final Duration CA_LIFETIME = Duration.ofDays(20L * 365);
    /** Under Apple's 825-day ceiling for TLS server certificates, with room to spare. */
    static final Duration LEAF_LIFETIME = Duration.ofDays(397);
    static final Duration RENEW_BEFORE = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private HouseholdTls() {}

    /** This home's TLS material, as loaded or made by {@link #ensure}. */
    public record Material(Path dir, X509Certificate ca, X509Certificate leaf, String caFingerprint,
                           char[] keystorePassword) {
        public Path caPem() { return dir.resolve(CA_CERT); }
        public Path leafChainPem() { return dir.resolve(LEAF_CERT); }
        public Path leafKeyPem() { return dir.resolve(LEAF_KEY); }
        public Path keystore() { return dir.resolve(KEYSTORE); }
        public Instant leafExpires() { return leaf.getNotAfter().toInstant(); }
    }

    /** Loads this home's CA and leaf, making or renewing what is missing or stale. */
    public static synchronized Material ensure(Path dataDir) throws IOException, GeneralSecurityException {
        return ensure(dataDir, localNames(), Instant.now());
    }

    static synchronized Material ensure(Path dataDir, Set<String> names, Instant now)
            throws IOException, GeneralSecurityException {
        var dir = dataDir.resolve(DIR);
        Files.createDirectories(dir);
        shareWithBusGroup(dir);
        var owner = dataDir;

        X509Certificate ca = null;
        PrivateKey caKey = null;
        var caPem = dir.resolve(CA_CERT);
        var caKeyPem = dir.resolve(CA_KEY);
        if (Files.isRegularFile(caPem) && Files.isRegularFile(caKeyPem)) {
            try {
                ca = readCertificates(Files.readString(caPem)).getFirst();
                caKey = readPrivateKey(Files.readString(caKeyPem));
            } catch (Exception e) {
                log.error("The household CA in {} cannot be read ({}); making a new one. Phones and household "
                    + "machines paired before must pair again.", dir, e.getMessage());
                ca = null;
            }
        }
        boolean newCa = false;
        if (ca == null) {
            var kp = newKeyPair();
            ca = issueCa(kp, now);
            caKey = kp.getPrivate();
            writeFile(caKeyPem, pem("PRIVATE KEY", caKey.getEncoded()), "rw-------", false, owner);
            writeFile(caPem, pem("CERTIFICATE", ca.getEncoded()), "rw-r--r--", false, owner);
            newCa = true;
            log.info("Made this home's household CA ({}), fingerprint {}", caPem, fingerprint(ca));
        }

        var leafPem = dir.resolve(LEAF_CERT);
        var leafKeyPem = dir.resolve(LEAF_KEY);
        X509Certificate leaf = null;
        PrivateKey leafKey = null;
        if (!newCa && Files.isRegularFile(leafPem) && Files.isRegularFile(leafKeyPem)) {
            try {
                leaf = readCertificates(Files.readString(leafPem)).getFirst();
                leafKey = readPrivateKey(Files.readString(leafKeyPem));
                var why = staleReason(leaf, ca, names, now);
                if (why != null) {
                    log.info("Issuing this machine's household certificate again: {}", why);
                    leaf = null;
                }
            } catch (Exception e) {
                leaf = null;
            }
        }
        var passFile = dir.resolve(KEYSTORE_PASS);
        var passExisted = Files.isRegularFile(passFile);
        var keystorePass = readOrMakeSecret(passFile, owner);
        if (leaf == null) {
            var kp = newKeyPair();
            leaf = issueLeaf(kp, ca, caKey, names, now);
            leafKey = kp.getPrivate();
            writeFile(leafKeyPem, pem("PRIVATE KEY", leafKey.getEncoded()), "rw-------", true, owner);
            writeFile(leafPem, pem("CERTIFICATE", leaf.getEncoded()) + pem("CERTIFICATE", ca.getEncoded()),
                "rw-r--r--", false, owner);
            writeKeystore(dir.resolve(KEYSTORE), leafKey, leaf, ca, keystorePass, owner);
            log.info("Issued this machine's household certificate until {} for {}", leaf.getNotAfter().toInstant(), names);
        } else if (!passExisted || !Files.isRegularFile(dir.resolve(KEYSTORE))) {
            writeKeystore(dir.resolve(KEYSTORE), leafKey, leaf, ca, keystorePass, owner);
        }
        return new Material(dir, ca, leaf, fingerprint(ca), keystorePass);
    }

    /** Reads the CA certificate without making anything; null when this home has none yet. */
    public static X509Certificate readCa(Path dataDir) {
        var f = dataDir.resolve(DIR).resolve(CA_CERT);
        if (!Files.isRegularFile(f)) return null;
        try {
            return readCertificates(Files.readString(f)).getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What a pairing invite carries about this home's TLS (D1): {@code home_ca_fp} when the home has
     * its CA, and {@code lan_https} ("https://&lt;LAN address&gt;:&lt;port&gt;") when HTTPS is served.
     */
    public static Map<String, String> inviteFields(Path dataDir, boolean httpsServed, int tlsPort) {
        var m = new LinkedHashMap<String, String>();
        var ca = readCa(dataDir);
        if (ca != null) m.put("home_ca_fp", fingerprint(ca));
        var ip = httpsServed ? preferredLanAddress() : null;
        if (ip != null) m.put("lan_https", "https://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + tlsPort);
        // The household bus as a phone reaches it (the websocket at the bus port + 1), so apps need not
        // assume 4223 (2026-09-28 rehearsal: a home on another bus port was unreachable).
        var busIp = ip != null ? ip : preferredLanAddress();
        if (busIp != null) {
            boolean plain = WyrdConfig.get().natsLanPlaintext();
            m.put("home_bus", (plain ? "ws://" : "wss://") + (busIp.contains(":") ? "[" + busIp + "]" : busIp)
                + ":" + (busPort() + 1));
        }
        return m;
    }

    /** The household bus's client port: the port in {@code WYRDSEKAI_NATS_URL}, else 4222. */
    static int busPort() {
        var url = System.getenv("WYRDSEKAI_NATS_URL");
        if (url != null && !url.isBlank()) {
            try {
                var p = URI.create(url.trim()).getPort();
                if (p > 0) return p;
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return 4222;
    }

    /**
     * The address a phone on the LAN should use: {@code WYRDSEKAI_LAN_IP} when set, else a private IPv4
     * on a real interface (not docker, bridge, VM or tunnel), else any IPv4 on one.
     */
    public static String preferredLanAddress() {
        var override = System.getenv("WYRDSEKAI_LAN_IP");
        if (override != null && !override.isBlank()) return override.trim();
        String fallback = null;
        try {
            for (var iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!iface.isUp() || iface.isLoopback() || isVirtual(iface.getName())) continue;
                } catch (Exception e) {
                    continue;
                }
                for (var addr : Collections.list(iface.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    if (addr.isSiteLocalAddress()) return addr.getHostAddress();
                    if (fallback == null) fallback = addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            log.debug("interfaces unavailable: {}", e.toString());
        }
        return fallback;
    }

    private static boolean isVirtual(String name) {
        if (name == null) return false;
        var n = name.toLowerCase(Locale.ROOT);
        for (var p : new String[]{"docker", "br-", "veth", "virbr", "tun", "tap", "lo", "vmnet", "utun", "tailscale", "zt", "wg"}) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /** {@code home_ca_fp} (D1): lowercase hex SHA-256 of the CA certificate's DER, no colons. */
    public static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts a fingerprint with or without colons, any case. */
    public static String normalizeFingerprint(String fp) {
        if (fp == null) return null;
        var s = fp.replace(":", "").trim().toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{64}") ? s : null;
    }

    /** Why a leaf must be issued again, or null when it is fine. */
    static String staleReason(X509Certificate leaf, X509Certificate ca, Set<String> names, Instant now) {
        try {
            leaf.verify(ca.getPublicKey());
        } catch (Exception e) {
            return "not signed by this home's CA";
        }
        if (leaf.getNotAfter().toInstant().isBefore(now.plus(RENEW_BEFORE))) {
            return "it expires " + leaf.getNotAfter().toInstant();
        }
        var have = sanNames(leaf);
        var missing = new ArrayList<String>();
        for (var n : names) {
            if (!have.contains(normalizeName(n))) missing.add(n);
        }
        return missing.isEmpty() ? null : "it does not name " + missing;
    }

    /** The names and addresses a household member may reach this machine by. */
    public static Set<String> localNames() {
        var names = new LinkedHashSet<String>();
        names.add("localhost");
        names.add("127.0.0.1");
        names.add("::1");
        try {
            var h = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            if (!h.isBlank() && !isIpLiteral(h)) {
                names.add(h);
                var shortName = h.contains(".") ? h.substring(0, h.indexOf('.')) : h;
                names.add(shortName);
                names.add(shortName + ".local");
            }
        } catch (Exception e) {
            log.debug("host name unavailable for the household certificate: {}", e.toString());
        }
        for (var env : new String[]{"WYRDSEKAI_HOSTNAME", "WYRDSEKAI_LAN_IP"}) {
            var v = System.getenv(env);
            if (v != null && !v.isBlank() && !v.equals("localhost")) names.add(v.trim().toLowerCase(Locale.ROOT));
        }
        try {
            for (var iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (var addr : Collections.list(iface.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    if (addr instanceof Inet4Address || addr instanceof Inet6Address) {
                        names.add(stripScope(addr.getHostAddress()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("interfaces unavailable for the household certificate: {}", e.toString());
        }
        return names;
    }

    /**
     * A TLS context that trusts only this home's CA (the node's own connections, household machines
     * that joined). The CA is this household's alone, so names are not checked: the pin is the identity.
     */
    public static SSLContext clientContext(X509Certificate ca) throws GeneralSecurityException {
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new CaTrust(ca, null)}, RANDOM);
        return ctx;
    }

    /**
     * A TLS context for first contact with a household whose CA is known only by its fingerprint
     * (from the household key or an invite): the server's chain must include a certificate with that
     * fingerprint, and the leaf must be signed by it.
     */
    public static SSLContext pinnedContext(String caFingerprint) throws GeneralSecurityException {
        var pin = normalizeFingerprint(caFingerprint);
        if (pin == null) throw new GeneralSecurityException("not a SHA-256 fingerprint: " + caFingerprint);
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new CaTrust(null, pin)}, RANDOM);
        return ctx;
    }

    /** Parses one or more PEM certificates. */
    public static List<X509Certificate> readCertificates(String pem) throws CertificateException {
        var cf = CertificateFactory.getInstance("X.509");
        var out = new ArrayList<X509Certificate>();
        for (var c : cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
            out.add((X509Certificate) c);
        }
        if (out.isEmpty()) throw new CertificateException("no certificate in PEM");
        return out;
    }

    public static String pem(String type, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static final class CaTrust extends X509ExtendedTrustManager {
        private final X509Certificate ca;
        private final String pin;

        CaTrust(X509Certificate ca, String pin) {
            this.ca = ca;
            this.pin = pin;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate presented");
            var anchor = ca;
            if (anchor == null) {
                for (var c : chain) {
                    if (pin.equals(fingerprint(c))) {
                        anchor = c;
                        break;
                    }
                }
                if (anchor == null) {
                    throw new CertificateException("this is not the household the key or invite names "
                        + "(no certificate with fingerprint " + pin + ")");
                }
            }
            var path = new ArrayList<X509Certificate>();
            for (var c : chain) {
                if (c.equals(anchor)) break;
                path.add(c);
            }
            if (path.isEmpty()) throw new CertificateException("the household CA was presented as the server certificate");
            try {
                var params = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
                params.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX")
                    .validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params);
            } catch (GeneralSecurityException e) {
                throw new CertificateException("not signed by the household CA: " + e.getMessage(), e);
            }
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket s) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket s) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) throws CertificateException { check(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return ca == null ? new X509Certificate[0] : new X509Certificate[]{ca}; }
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        var g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    private static X509Certificate issueCa(KeyPair kp, Instant now) throws GeneralSecurityException, IOException {
        var name = new X500Name("CN=Wyrdsekai household CA " + HexFormat.of().formatHex(randomBytes(4)) + ",O=Wyrdsekai");
        var b = new JcaX509v3CertificateBuilder(name, serial(), Date.from(now.minus(Duration.ofHours(1))),
            Date.from(now.plus(CA_LIFETIME)), name, kp.getPublic());
        var ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
        return sign(b, kp.getPrivate());
    }

    private static X509Certificate issueLeaf(KeyPair kp, X509Certificate ca, PrivateKey caKey, Set<String> names,
                                             Instant now) throws GeneralSecurityException, IOException {
        var cn = names.stream().filter(n -> !isIpLiteral(n) && !n.equals("localhost")).findFirst().orElse("localhost");
        var b = new JcaX509v3CertificateBuilder(ca, serial(), Date.from(now.minus(Duration.ofHours(1))),
            Date.from(now.plus(LEAF_LIFETIME)), new X500Name("CN=" + cn + ",O=Wyrdsekai"), kp.getPublic());
        var ext = new JcaX509ExtensionUtils();
        var sans = new ArrayList<GeneralName>();
        for (var n : names) {
            sans.add(isIpLiteral(n) ? new GeneralName(GeneralName.iPAddress, stripScope(n))
                : new GeneralName(GeneralName.dNSName, n));
        }
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        b.addExtension(Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray(new GeneralName[0])));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(ca));
        return sign(b, caKey);
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder b, PrivateKey key) throws GeneralSecurityException {
        try {
            var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(key);
            return new JcaX509CertificateConverter().getCertificate(b.build(signer));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static BigInteger serial() {
        return new BigInteger(1, randomBytes(16));
    }

    private static byte[] randomBytes(int n) {
        var b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static Set<String> sanNames(X509Certificate cert) {
        var out = new LinkedHashSet<String>();
        try {
            var sans = cert.getSubjectAlternativeNames();
            if (sans == null) return out;
            for (var entry : sans) {
                if (entry.size() >= 2 && entry.get(1) instanceof String v) out.add(normalizeName(v));
            }
        } catch (CertificateException e) {
            log.debug("unreadable SAN: {}", e.toString());
        }
        return out;
    }

    private static String normalizeName(String n) {
        if (isIpLiteral(n)) {
            try {
                return InetAddress.ofLiteral(stripScope(n)).getHostAddress();
            } catch (IllegalArgumentException e) {
                return n;
            }
        }
        return n.toLowerCase(Locale.ROOT);
    }

    static boolean isIpLiteral(String s) {
        if (s == null || s.isBlank()) return false;
        if (!s.contains(":") && !s.matches("[0-9.]+")) return false;
        try {
            InetAddress.ofLiteral(stripScope(s));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripScope(String ip) {
        var pct = ip.indexOf('%');
        return pct >= 0 ? ip.substring(0, pct) : ip;
    }

    private static PrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        var b64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static char[] readOrMakeSecret(Path f, Path owner) throws IOException {
        if (Files.isRegularFile(f)) {
            var s = Files.readString(f).trim();
            if (!s.isEmpty()) return s.toCharArray();
        }
        var s = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        writeFile(f, s + "\n", "rw-------", false, owner);
        return s.toCharArray();
    }

    private static void writeKeystore(Path f, PrivateKey key, X509Certificate leaf, X509Certificate ca,
                                      char[] pass, Path owner) throws IOException, GeneralSecurityException {
        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(KEYSTORE_ALIAS, key, pass, new Certificate[]{leaf, ca});
        var bos = new ByteArrayOutputStream();
        ks.store(bos, pass);
        writeFile(f, bos.toByteArray(), "rw-------", false, owner);
    }

    /**
     * A folder the packaged bus service (its own user, Group={@value #BUS_GROUP}) has to pass through:
     * traverse for that group only, nothing for others. Where the group does not exist (the node runs
     * its own bus) the folder is the node's alone. It used to be opened to everyone (o+x), which the
     * boot-time closing of the data folder to other users then took away again, and the service
     * could not reach its files (second-node, 2026-09-28).
     */
    static void shareWithBusGroup(Path dir) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        var group = busGroup();
        if (group != null) {
            try {
                Files.getFileAttributeView(dir, PosixFileAttributeView.class).setGroup(group);
                setMode(dir, "rwx--x---");
                return;
            } catch (IOException | SecurityException e) {
                // A node not run as root, whose user is not in the group (a checkout on a machine with
                // the package installed): the packaged bus is not its bus, so close the folder to it.
                log.debug("{}: the {} group could not be given ({}); closed to this user",
                    dir, BUS_GROUP, e.toString());
            }
        }
        setMode(dir, "rwx------");
    }

    static void writeFile(Path f, String content, String mode, boolean busGroup, Path owner) throws IOException {
        writeFile(f, content.getBytes(StandardCharsets.UTF_8), mode, busGroup, owner);
    }

    /**
     * Writes through a temporary file created with {@code mode} from the start, then moves it into
     * place. The file ends up owned like {@code owner} (a tool run as root must not leave the node's
     * user unable to read its own keys). {@code busGroup}: when the {@value #BUS_GROUP} group exists,
     * that group may read the file (0640).
     */
    static void writeFile(Path f, byte[] content, String mode, boolean busGroup, Path owner) throws IOException {
        Files.createDirectories(f.getParent());
        var tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        var posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.write(tmp, content);
        if (posix) {
            var group = busGroup ? busGroup() : null;
            var finalMode = mode;
            if (group != null) {
                try {
                    Files.getFileAttributeView(tmp, PosixFileAttributeView.class).setGroup(group);
                    finalMode = "rw-r-----";
                } catch (IOException | SecurityException e) {
                    log.debug("{} left without the {} group: {}", f, BUS_GROUP, e.toString());
                }
            }
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(finalMode));
        }
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (owner != null) {
            try {
                Files.setOwner(f, Files.getOwner(owner));
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                log.debug("{} owner left as is: {}", f, e.toString());
            }
        }
    }

    static void setMode(Path p, String mode) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("{} mode left as is: {}", p, e.toString());
        }
    }

    private static GroupPrincipal busGroup() {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByGroupName(BUS_GROUP);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }
}
