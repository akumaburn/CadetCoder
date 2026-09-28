package com.eonmux.cadetcoder.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * Whether this tool may fetch a URL, and the exact address it must connect to if so.
 *
 * <h2>Why the address is pinned and not just checked</h2>
 *
 * <p>Validating a host name and then opening a connection to it resolves DNS twice, and the second
 * answer need not be the first. A name whose record flips to {@code 169.254.169.254} between the
 * two passes the check and fetches the cloud metadata service anyway. So the host is resolved
 * exactly once, here, and the caller connects to {@link #pinned()} -- the same address that was
 * validated -- rather than to the name.</p>
 *
 * <h2>Why loopback is allowed</h2>
 *
 * <p>This is a developer tool, routinely pointed at a server running on the same machine. Private
 * and link-local ranges are refused; {@code 127.0.0.0/8} and {@code ::1} are not.</p>
 *
 * <h2>Why an unresolvable host is neither allowed nor refused</h2>
 *
 * <p>A name that does not resolve cannot be an SSRF target. Reporting it here would say "refused"
 * about something that is merely misspelled, so it comes back with no refusal and no pinned
 * address, and the fetch reports the resolution failure in its own words.</p>
 */
public final class OutboundAddress {

    private final String      refusal;
    private final InetAddress pinned;

    private OutboundAddress(String refusal, InetAddress pinned) {
        this.refusal = refusal;
        this.pinned  = pinned;
    }

    /**
     * Screens one target.
     *
     * @param target the URL about to be fetched
     * @return the verdict, carrying either a refusal or the address to connect to
     */
    public static OutboundAddress of(URL target) {
        if (target == null) {
            return new OutboundAddress("Invalid URL: no target to validate", null);
        }
        String host = target.getHost();
        if (host == null || host.isEmpty()) {
            return new OutboundAddress("Invalid URL: missing host", null);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new OutboundAddress(null, null);
        }
        if (addresses == null || addresses.length == 0) {
            return new OutboundAddress(null, null);
        }

        for (InetAddress address : addresses) {
            String refusal = refusalFor(host, address);
            if (refusal != null) {
                return new OutboundAddress(refusal, null);
            }
        }
        // Every resolved address passed. Pin to the first so the connection targets exactly the IP
        // that was validated, defeating a rebind between this check and the connect.
        return new OutboundAddress(null, addresses[0]);
    }

    /** Why this address is not somewhere the tool will fetch from, or {@code null}. */
    private static String refusalFor(String host, InetAddress address) {
        String where = host + " (" + address.getHostAddress() + ")";
        if (address.isLoopbackAddress()) {
            return null;
        }
        // 169.254.0.0/16, which includes 169.254.169.254, and fe80::/10.
        if (address.isLinkLocalAddress()) {
            return "Refusing to fetch link-local/metadata address: " + where;
        }
        // RFC1918 (10/8, 172.16/12, 192.168/16) and IPv6 unique-local (fc00::/7).
        if (address.isSiteLocalAddress() || isUniqueLocalIPv6(address)) {
            return "Refusing to fetch private address: " + where;
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return "Refusing to fetch non-routable address: " + where;
        }
        return null;
    }

    /** IPv6 unique-local addresses (fc00::/7), which {@code isSiteLocalAddress} does not cover. */
    private static boolean isUniqueLocalIPv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * Why this target was refused.
     *
     * @return the message to show, or {@code null} when the target is allowed
     */
    public String refusal() {
        return refusal;
    }

    /**
     * The address the connection must be made to.
     *
     * @return the pinned address, or {@code null} when the host did not resolve
     */
    public InetAddress pinned() {
        return pinned;
    }
}
