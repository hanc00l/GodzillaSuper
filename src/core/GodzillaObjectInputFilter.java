/*
 * Reconstructed for the GSL5 CLI fork: decompiled (CFR 0.152) from the upstream
 * Release jar (origin-jar/gsl5.jar) because src/ shipped without this class.
 * Outer class + static nested ObjectInputFilterInvocationHandler assembled from
 * two CFR outputs; compiler-synthetic access$000/access$100 bridges omitted.
 */
package core;

import core.ui.component.dialog.GOptionPane;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import util.Log;
import util.functions;

public class GodzillaObjectInputFilter {
    private static boolean initialized = false;
    public static final LinkedHashSet<String> whitePackageList = new LinkedHashSet();
    public static final LinkedHashSet<String> blackClassList = new LinkedHashSet();
    public static final LinkedHashSet<Class> blackClassesList = new LinkedHashSet();
    private static Class<?> JavaObjectInputFilterClass;
    private static Class<?> JavaObjectInputFilterConfigClass;
    private static Class<?> JavaObjectInputFilterFilterInfoClass;
    private static Class<Enum> JavaObjectInputFilterStatusClass;
    private static Method setSerialFilterMethod;

    public static void disableWeblogicFilter() {
        try {
            Class<?> WebLogicObjectInputFilterClass = Class.forName("weblogic.utils.io.oif.WebLogicObjectInputFilter");
            functions.getMethodByClass(WebLogicObjectInputFilterClass, (String)"initialize", (Class[])new Class[0]).invoke(null, null);
            Field isJreFilteringAvailableField = WebLogicObjectInputFilterClass.getDeclaredField("isJreFilteringAvailable");
            Field isWebLogicJreFilterConfiguredField = WebLogicObjectInputFilterClass.getDeclaredField("isWebLogicJreFilterConfigured");
            Field initializedField = WebLogicObjectInputFilterClass.getDeclaredField("initialized");
            isJreFilteringAvailableField.setAccessible(true);
            isWebLogicJreFilterConfiguredField.setAccessible(true);
            initializedField.setAccessible(true);
            isJreFilteringAvailableField.set(null, false);
            isWebLogicJreFilterConfiguredField.set(null, false);
            initializedField.set(null, true);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public static void installObjectInputFilter() {
        if (!initialized) {
            try {
                JavaObjectInputFilterClass = Class.forName("sun.misc.ObjectInputFilter");
            }
            catch (ClassNotFoundException e) {
                try {
                    JavaObjectInputFilterClass = Class.forName("java.io.ObjectInputFilter");
                }
                catch (ClassNotFoundException classNotFoundException) {
                    classNotFoundException.printStackTrace();
                }
            }
            if (JavaObjectInputFilterClass == null) {
                int opt = GOptionPane.showOptionDialog(null, (Object)"\u60a8\u7684Java\u7248\u672c\u8fc7\u4f4e \u53ef\u80fd\u4f1a\u53d7\u5230\u53cd\u5e8f\u5217\u5316\u6f0f\u6d1e\u653b\u51fb \u8bf7\u5347\u7ea7Java\u8fd0\u884c\u65f6!!!", (String)"\u6f0f\u6d1e\u8b66\u544a", (int)0, (int)2, null, (Object[])new Object[]{"\u7ee7\u7eed\u4f7f\u7528", "\u9000\u51fa"}, (Object)"\u9000\u51fa");
                if (opt != 0) {
                    System.exit(0);
                }
            } else {
                try {
                    JavaObjectInputFilterConfigClass = Class.forName(JavaObjectInputFilterClass.getName() + "$Config");
                    JavaObjectInputFilterFilterInfoClass = Class.forName(JavaObjectInputFilterClass.getName() + "$FilterInfo");
                    JavaObjectInputFilterStatusClass = (Class<Enum>) Class.forName(JavaObjectInputFilterClass.getName() + "$Status");
                    setSerialFilterMethod = JavaObjectInputFilterConfigClass.getMethod("setSerialFilter", JavaObjectInputFilterClass);
                    Object proxyJavaObjectInputFilter = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{JavaObjectInputFilterClass}, (InvocationHandler)new ObjectInputFilterInvocationHandler());
                    setSerialFilterMethod.invoke(null, proxyJavaObjectInputFilter);
                }
                catch (Throwable e) {
                    GOptionPane.showThrowableMessageDialog(null, (String)"\u5728\u521b\u5efaObjectInputFilter\u65f6\u53d1\u751f\u9519\u8bef", (Throwable)e);
                    System.exit(-1);
                }
            }
            GodzillaObjectInputFilter.disableWeblogicFilter();
            initialized = true;
        }
    }

    static {
        setSerialFilterMethod = null;
        whitePackageList.add("java.lang.");
        whitePackageList.add("int");
        whitePackageList.add("byte");
        whitePackageList.add("short");
        whitePackageList.add("long");
        whitePackageList.add("double");
        whitePackageList.add("java.util.");
        whitePackageList.add("weblogic.rjvm.");
        whitePackageList.add("weblogic.jndi.internal.");
        whitePackageList.add("weblogic.rmi.cluster.");
        whitePackageList.add("weblogic.rmi.internal.");
        whitePackageList.add("weblogic.utils.collections.");
        whitePackageList.add("weblogic.common.internal.");
        whitePackageList.add("javax.naming.");
        whitePackageList.add("weblogic.rmi.utils.");
        whitePackageList.add("com.sun.proxy.$Proxy");
        whitePackageList.add("java.rmi.server.");
        whitePackageList.add("javax.management.remote.rmi.RMIConnection");
        whitePackageList.add("org.apache.commons.collections.FunctorException");
        whitePackageList.add("oracle.sql.converter.");
        blackClassList.add("java.net.URL");
        blackClassList.add("javax.naming.Reference");
        blackClassList.add("javax.naming.LinkRef");
        blackClassList.add("javax.naming.RefAddr");
        blackClassList.add("javax.naming.BinaryRefAddr");
        blackClassList.add("javax.naming.StringRefAddr");
        blackClassList.add("com.sun.jndi.rmi.registry.ReferenceWrapper");
        blackClassList.stream().forEach(className -> {
            try {
                blackClassesList.add(Class.forName(className));
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        });
        System.setProperty("com.sun.jndi.ldap.object.trustURLCodebase", String.valueOf(false));
        System.setProperty("com.sun.jndi.cosnaming.object.trustURLCodebase", String.valueOf(false));
        System.setProperty("com.sun.jndi.rmi.object.trustURLCodebase", String.valueOf(false));
        System.setProperty("java.rmi.server.useCodebaseOnly", String.valueOf(true));
    }

    public static final class ObjectInputFilterInvocationHandler implements InvocationHandler {
        private static MethodHandle serialClassMethodHandle;
        private static MethodHandle arrayLengthMethodHandle;
        private static MethodHandle depthMethodHandle;
        private static MethodHandle referencesMethodHandle;
        private static MethodHandle streamBytesMethodHandle;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("checkInput") && args.length == 1) {
                Object filterInfo = args[0];
                Class<?> targetClass = this.serialClass(filterInfo);
                if (targetClass != null) {
                    String finalClassName;
                    String className = targetClass.getName();
                    if (targetClass.isArray()) {
                        for (Class<?> targetTmpClass = targetClass.getComponentType(); targetTmpClass != null; targetTmpClass = targetTmpClass.getComponentType()) {
                            if (targetTmpClass.isArray()) {
                                continue;
                            }
                            className = targetTmpClass.getName();
                            break;
                        }
                    }
                    if ((finalClassName = className).endsWith("_WLStub")) {
                        return Enum.valueOf(JavaObjectInputFilterStatusClass, "ALLOWED");
                    }
                    try {
                        if (Throwable.class.isAssignableFrom(Class.forName(finalClassName, true, Thread.currentThread().getContextClassLoader()))) {
                            return Enum.valueOf(JavaObjectInputFilterStatusClass, "ALLOWED");
                        }
                    }
                    catch (ClassNotFoundException classNotFoundException) {
                        // empty catch block
                    }
                    if (whitePackageList.stream().anyMatch(v -> finalClassName.startsWith((String)v))) {
                        if (blackClassList.stream().anyMatch(v -> v.equals(finalClassName))) {
                            Log.log((String)("REJECTED ->" + finalClassName));
                            return Enum.valueOf(JavaObjectInputFilterStatusClass, "REJECTED");
                        }
                        if (blackClassesList.stream().anyMatch(c -> c.isAssignableFrom(targetClass))) {
                            Log.log((String)("REJECTED ->" + finalClassName));
                            return Enum.valueOf(JavaObjectInputFilterStatusClass, "REJECTED");
                        }
                        return Enum.valueOf(JavaObjectInputFilterStatusClass, "ALLOWED");
                    }
                    Log.log((String)("REJECTED ->" + finalClassName));
                    return Enum.valueOf(JavaObjectInputFilterStatusClass, "REJECTED");
                }
                return Enum.valueOf(JavaObjectInputFilterStatusClass, "ALLOWED");
            }
            return null;
        }

        public Class<?> serialClass(Object filterInfo) throws Throwable {
            return (Class<?>) serialClassMethodHandle.bindTo(filterInfo).invoke();
        }

        public long arrayLength(Object filterInfo) throws Throwable {
            return (Long) arrayLengthMethodHandle.bindTo(filterInfo).invoke();
        }

        public long depth(Object filterInfo) throws Throwable {
            return (Long) depthMethodHandle.bindTo(filterInfo).invoke();
        }

        public long references(Object filterInfo) throws Throwable {
            return (Long) referencesMethodHandle.bindTo(filterInfo).invoke();
        }

        public long streamBytes(Object filterInfo) throws Throwable {
            return (Long) streamBytesMethodHandle.bindTo(filterInfo).invoke();
        }

        static {
            try {
                serialClassMethodHandle = MethodHandles.lookup().unreflect(functions.getMethodByClass((Class)JavaObjectInputFilterFilterInfoClass, (String)"serialClass", null));
                arrayLengthMethodHandle = MethodHandles.lookup().unreflect(functions.getMethodByClass((Class)JavaObjectInputFilterFilterInfoClass, (String)"arrayLength", null));
                depthMethodHandle = MethodHandles.lookup().unreflect(functions.getMethodByClass((Class)JavaObjectInputFilterFilterInfoClass, (String)"depth", null));
                referencesMethodHandle = MethodHandles.lookup().unreflect(functions.getMethodByClass((Class)JavaObjectInputFilterFilterInfoClass, (String)"references", null));
                streamBytesMethodHandle = MethodHandles.lookup().unreflect(functions.getMethodByClass((Class)JavaObjectInputFilterFilterInfoClass, (String)"streamBytes", null));
            }
            catch (IllegalAccessException e) {
                GOptionPane.showThrowableMessageDialog(null, (String)"\u5728\u521b\u5efaObjectInputFilter\u65f6\u53d1\u751f\u9519\u8bef", (Throwable)e);
                System.exit(-1);
            }
        }
    }
}
