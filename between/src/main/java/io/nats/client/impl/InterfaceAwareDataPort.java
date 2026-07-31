package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.support.NatsUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * NATS DataPort that pins the socket to the correct network interface using
 * macOS IP_BOUND_IF (or Linux SO_BINDTODEVICE equivalent).
 *
 * On macOS with both Ethernet and WiFi active, the OS kills TCP connections
 * on the lower-priority interface (network service order). IP_BOUND_IF pins
 * the socket to the correct interface at the kernel level, preventing this.
 *
 * Uses Java FFM API (Java 22+) for the setsockopt call. Falls back to
 * address-only binding if FFM isn't available.
 */
public class InterfaceAwareDataPort implements DataPort {

    private static final Logger log = LoggerFactory.getLogger(InterfaceAwareDataPort.class);

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private static volatile InetAddress cachedLocalBind;
    private static volatile NetworkInterface cachedInterface;
    private static volatile boolean detected = false;

    // macOS: IP_BOUND_IF = 25, IPPROTO_IP = 0
    private static final int IPPROTO_IP = 0;
    private static final int IP_BOUND_IF = 25;
    private static final boolean IS_MACOS = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // FFM handle for setsockopt (lazily initialized)
    private static volatile MethodHandle setsockoptHandle;
    private static volatile boolean ffmAvailable = true;

    @Override
    public void afterConstruct(Options options) {}

    @Override
    public void connect(String serverURI, NatsConnection conn, long timeoutNanos) throws IOException {
        try {
            var natsUri = new NatsUri(serverURI);
            connect(conn, natsUri, timeoutNanos);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI: " + serverURI, e);
        }
    }

    @Override
    public void connect(NatsConnection conn, NatsUri natsUri, long timeoutNanos) throws IOException {
        var host = natsUri.getUri().getHost();
        var port = natsUri.getUri().getPort();
        if (port <= 0) port = 4222;

        if (!detected) {
            detectLocalInterface(host);
        }

        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setReuseAddress(true);

        if (cachedLocalBind != null) {
            try {
                // Bind to the correct local address
                socket.bind(new InetSocketAddress(cachedLocalBind, 0));

                // Pin to the interface at kernel level (macOS IP_BOUND_IF)
                if (IS_MACOS && cachedInterface != null) {
                    pinToInterface(socket, cachedInterface);
                }

                log.debug("Bound to {} for NATS {}:{}", cachedLocalBind.getHostAddress(), host, port);
            } catch (IOException bindErr) {
                log.warn("Bind to {} failed: {} — using default routing",
                    cachedLocalBind.getHostAddress(), bindErr.getMessage());
                try { socket.close(); } catch (Exception ignored) {}
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setReuseAddress(true);
            }
        }

        var timeoutMs = (int) (timeoutNanos / 1_000_000);
        if (timeoutMs <= 0) timeoutMs = 10_000;
        socket.connect(new InetSocketAddress(host, port), timeoutMs);

        in = socket.getInputStream();
        out = socket.getOutputStream();

        if (cachedLocalBind != null) {
            log.info("NATS connected {}:{} via {} ({})", host, port,
                cachedLocalBind.getHostAddress(),
                cachedInterface != null ? cachedInterface.getName() + " pinned" : "bind only");
        }
    }

    /**
     * Pin the socket to a specific network interface using macOS IP_BOUND_IF.
     * This prevents macOS from rerouting the TCP connection to a higher-priority
     * interface (e.g., Ethernet over WiFi) based on the network service order.
     */
    private static void pinToInterface(Socket socket, NetworkInterface iface) {
        if (!ffmAvailable) return;

        try {
            var handle = getSetsockoptHandle();
            if (handle == null) return;

            // Get the raw file descriptor from the socket
            var fd = getFd(socket);
            if (fd < 0) return;

            var ifIndex = iface.getIndex();

            try (var arena = Arena.ofConfined()) {
                var optval = arena.allocate(ValueLayout.JAVA_INT);
                optval.set(ValueLayout.JAVA_INT, 0, ifIndex);

                var result = (int) handle.invoke(fd, IPPROTO_IP, IP_BOUND_IF,
                    optval, (int) ValueLayout.JAVA_INT.byteSize());

                if (result == 0) {
                    log.info("IP_BOUND_IF set: socket pinned to {} (index={})",
                        iface.getName(), ifIndex);
                } else {
                    log.debug("IP_BOUND_IF failed: result={}", result);
                }
            }
        } catch (Throwable e) {
            log.debug("IP_BOUND_IF not available: {}", e.getMessage());
            ffmAvailable = false;
        }
    }

    /**
     * Get the native file descriptor from a Java Socket using reflection.
     * Path: Socket.impl (SocksSocketImpl) → DelegatingSocketImpl.delegate (NioSocketImpl) → fd
     */
    private static int getFd(Socket socket) {
        try {
            var implField = Socket.class.getDeclaredField("impl");
            implField.setAccessible(true);
            var impl = implField.get(socket);

            // SocksSocketImpl extends DelegatingSocketImpl which has a 'delegate' field
            // The delegate (NioSocketImpl) holds the actual fd
            Object target = impl;
            try {
                var delField = impl.getClass().getSuperclass().getDeclaredField("delegate");
                delField.setAccessible(true);
                var delegate = delField.get(impl);
                if (delegate != null) target = delegate;
            } catch (NoSuchFieldException ignored) {}

            // Walk hierarchy to find 'fd' field
            var cls = target.getClass();
            while (cls != null) {
                try {
                    var fdField = cls.getDeclaredField("fd");
                    fdField.setAccessible(true);
                    var fdObj = (FileDescriptor) fdField.get(target);
                    if (fdObj != null) {
                        var fdIntField = FileDescriptor.class.getDeclaredField("fd");
                        fdIntField.setAccessible(true);
                        return fdIntField.getInt(fdObj);
                    }
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            log.debug("Cannot find fd in socket impl hierarchy");
            return -1;
        } catch (Exception e) {
            log.debug("Cannot get socket fd: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Get or create the FFM handle for setsockopt.
     */
    private static MethodHandle getSetsockoptHandle() {
        if (setsockoptHandle != null) return setsockoptHandle;
        try {
            var linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            var addr = lookup.find("setsockopt").orElse(null);
            if (addr == null) {
                // Try libc
                addr = SymbolLookup.loaderLookup().find("setsockopt").orElse(null);
            }
            if (addr == null) {
                ffmAvailable = false;
                return null;
            }
            setsockoptHandle = linker.downcallHandle(addr,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,   // return: int
                    ValueLayout.JAVA_INT,   // sockfd
                    ValueLayout.JAVA_INT,   // level (IPPROTO_IP)
                    ValueLayout.JAVA_INT,   // optname (IP_BOUND_IF)
                    ValueLayout.ADDRESS,    // optval pointer
                    ValueLayout.JAVA_INT    // optlen
                ));
            return setsockoptHandle;
        } catch (Throwable e) {
            log.debug("FFM setsockopt not available: {}", e.getMessage());
            ffmAvailable = false;
            return null;
        }
    }

    @Override
    public void upgradeToSecure() throws IOException {
        var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        var sslSocket = (SSLSocket) factory.createSocket(
            socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.startHandshake();
        socket = sslSocket;
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return in.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] src, int length) throws IOException {
        out.write(src, 0, length);
    }

    @Override
    public void shutdownInput() throws IOException {
        if (socket != null && !socket.isClosed()) socket.shutdownInput();
    }

    @Override
    public void close() throws IOException {
        if (socket != null && !socket.isClosed()) socket.close();
    }

    @Override
    public void forceClose() throws IOException {
        close();
    }

    @Override
    public void flush() throws IOException {
        if (out != null) out.flush();
    }

    private static synchronized void detectLocalInterface(String targetHost) {
        if (detected) return;
        detected = true;

        if (targetHost == null || "localhost".equals(targetHost)
            || "127.0.0.1".equals(targetHost) || "0.0.0.0".equals(targetHost)) {
            return;
        }

        try {
            var targetAddr = InetAddress.getByName(targetHost);
            var targetBytes = targetAddr.getAddress();
            if (targetBytes.length != 4) return;

            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        var localBytes = addr.getAddress();
                        if (localBytes[0] == targetBytes[0]
                            && localBytes[1] == targetBytes[1]
                            && localBytes[2] == targetBytes[2]) {
                            cachedLocalBind = addr;
                            cachedInterface = iface;
                            log.info("Multi-homed: will bind to {} ({}) for NATS target {}",
                                addr.getHostAddress(), iface.getName(), targetHost);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Interface detection failed: {}", e.getMessage());
        }
    }
}
