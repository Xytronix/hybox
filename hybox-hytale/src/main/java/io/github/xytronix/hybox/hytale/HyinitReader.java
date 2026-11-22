package io.github.xytronix.hybox.hytale;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class HyinitReader {
    static final String API_CLASS = "cc.irori.hyinit.shared.HyinitDiagnostics";

    private static final System.Logger LOGGER = System.getLogger(HyinitReader.class.getName());
    private static volatile HyinitReader shared;

    private final boolean classMissing;
    private final Method snapshot;
    private final Method mixinsFor;
    private final Method methodMap;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private HyinitReader(boolean classMissing, Method snapshot, Method mixinsFor, Method methodMap) {
        this.classMissing = classMissing;
        this.snapshot = snapshot;
        this.mixinsFor = mixinsFor;
        this.methodMap = methodMap;
    }

    static HyinitReader get() {
        HyinitReader reader = shared;
        if (reader == null) {
            reader = load(HyinitReader.class.getClassLoader(), API_CLASS);
            shared = reader;
        }
        return reader;
    }

    static HyinitReader load(ClassLoader loader, String className) {
        try {
            Class<?> api = Class.forName(className, false, loader);
            Object version = api.getMethod("apiVersion").invoke(null);
            if (!(version instanceof Integer number) || number < 1) {
                LOGGER.log(System.Logger.Level.DEBUG, "Hyinit diagnostics API version " + version + " is not supported.");
                return new HyinitReader(false, null, null, null);
            }
            return new HyinitReader(false, api.getMethod("snapshot"),
                api.getMethod("mixinsFor", String.class, String.class), api.getMethod("methodMap"));
        } catch (ClassNotFoundException e) {
            return new HyinitReader(true, null, null, null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "Hyinit diagnostics are not readable.", e);
            return new HyinitReader(false, null, null, null);
        }
    }

    boolean present() {
        return snapshot != null;
    }

    boolean classMissing() {
        return classMissing;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> snapshot() {
        return (Map<String, Object>) call(snapshot, Map.class);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, String>> mixinsFor(String className, String methodName) {
        List<Map<String, String>> entries = (List<Map<String, String>>) call(mixinsFor, List.class, className, methodName);
        return entries == null ? List.of() : entries;
    }

    @SuppressWarnings("unchecked")
    Map<String, Map<String, List<Map<String, String>>>> methodMap() {
        return (Map<String, Map<String, List<Map<String, String>>>>) call(methodMap, Map.class);
    }

    private Object call(Method method, Class<?> type, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(null, args);
            return type.isInstance(result) ? result : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (failureLogged.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.DEBUG, "Hyinit diagnostics call " + method.getName() + " failed.", e);
            }
            return null;
        }
    }
}
