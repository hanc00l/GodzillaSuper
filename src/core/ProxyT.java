/*
 * Reconstructed for the GSL5 CLI fork: decompiled (CFR 0.152) from the upstream
 * Release jar (origin-jar/gsl5.jar) because src/ shipped without this class.
 * Adapted: Db.tryGetSetingValue -> Db.getSetingValue (same (String, String) signature in this tree).
 */
package core;

import core.shell.ShellEntity;
import java.net.InetSocketAddress;
import java.net.Proxy;

public class ProxyT {
    private static final String[] PTOXY_TYPES = new String[]{"NO_PROXY", "HTTP", "SOCKS", "GLOBAL_PROXY"};

    private ProxyT() {
    }

    public static Proxy getProxy(ShellEntity context) {
        try {
            String type = context.getProxyType();
            InetSocketAddress inetSocketAddress = new InetSocketAddress(context.getProxyHost(), context.getProxyPort());
            if ("SOCKS".equalsIgnoreCase(type)) {
                return new Proxy(Proxy.Type.SOCKS, inetSocketAddress);
            }
            if ("HTTP".equalsIgnoreCase(type)) {
                return new Proxy(Proxy.Type.HTTP, inetSocketAddress);
            }
            if ("GLOBAL_PROXY".equalsIgnoreCase(type)) {
                inetSocketAddress = new InetSocketAddress(Db.getSetingValue((String)"globalProxyHost", (String)"127.0.0.1"), Integer.parseInt(Db.getSetingValue((String)"globalProxyPort", (String)"8888")));
                type = Db.getSetingValue((String)"globalProxyType", (String)"NO_PROXY");
                if ("SOCKS".equalsIgnoreCase(type)) {
                    return new Proxy(Proxy.Type.SOCKS, inetSocketAddress);
                }
                if ("HTTP".equalsIgnoreCase(type)) {
                    return new Proxy(Proxy.Type.HTTP, inetSocketAddress);
                }
            }
            return Proxy.NO_PROXY;
        }
        catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    public static String[] getAllProxyType() {
        return PTOXY_TYPES;
    }
}
