package dev.otectus.mcamobcompat.integration.workers;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place in this mod that knows Villager Workers' class names, as strings only, under the
 * same rule and the same exact build-time allowlist entry as {@code integration/recruits/RecruitsBinding}.
 *
 * <p>Workers' workers are Villager Recruits recruits by inheritance, so owners, follow states, groups
 * and hiring all go through {@code RecruitsBinding}; this class binds only what Workers adds: whether
 * a worker is at work, and the release of its work and home areas. Pinned to the audited Workers
 * 2.0.3 for Forge 1.20.1; the older 1.7.x line has a different design and is not claimed.
 */
public final class WorkersBinding {

    private static final String ROOT = "com.talhanation.workers.";

    private static volatile boolean resolved;
    private static volatile Class<?> workerClass;
    private static volatile Method isWorking;
    private static volatile Method currentWorkArea;
    private static volatile Method releaseHomeArea;

    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

    private WorkersBinding() {
    }

    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        bindAgainst(WorkersBinding.class.getClassLoader());
    }

    static synchronized void bindAgainst(ClassLoader loader) {
        try {
            workerClass = Class.forName(ROOT + "entities.AbstractWorkerEntity", false, loader);
        } catch (ClassNotFoundException | LinkageError t) {
            warnMissing("AbstractWorkerEntity", t);
            return;
        }
        isWorking = typed("isWorking", boolean.class);
        releaseHomeArea = typed("releaseHomeArea", void.class);
        // Its return type is Workers' own work-area class, so it is bound by name and arity, then read
        // back through a by-name call on whatever it returns.
        for (Method method : workerClass.getMethods()) {
            if (method.getName().equals("getCurrentWorkArea") && method.getParameterCount() == 0) {
                currentWorkArea = method;
            }
        }
        if (currentWorkArea == null) {
            warnMissing("getCurrentWorkArea", new NoSuchMethodException("getCurrentWorkArea/0"));
        }
    }

    /** Forgets every binding, so a probe test can bind a jar in its own loader. Test-only. */
    static synchronized void resetForProbe() {
        resolved = false;
        workerClass = null;
        isWorking = null;
        currentWorkArea = null;
        releaseHomeArea = null;
        FAILURES.clear();
    }

    public static boolean isAvailable() {
        return workerClass != null && isWorking != null;
    }

    public static Optional<String> problem() {
        if (workerClass == null) {
            return Optional.of("AbstractWorkerEntity not found");
        }
        return isWorking == null ? Optional.of("isWorking missing") : Optional.empty();
    }

    /** Whether this entity is a Workers worker. */
    public static boolean isWorker(Entity entity) {
        Class<?> type = workerClass;
        return type != null && type.isInstance(entity);
    }

    public static boolean isWorkerClass(Class<?> type) {
        Class<?> worker = workerClass;
        return worker != null && type != null && worker.isAssignableFrom(type);
    }

    /** Whether Workers itself reports this worker at work. */
    public static boolean working(Entity worker) {
        Method method = isWorking;
        if (method == null || !isWorker(worker)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(worker));
        } catch (Throwable t) {
            isWorking = null;
            retire("isWorking", t);
            return false;
        }
    }

    /**
     * Frees the worker's current work area and its home, through Workers' own methods, so another
     * worker can take them once this one has gone back to civilian life.
     */
    public static void releaseAssignments(Entity worker) {
        if (!isWorker(worker)) {
            return;
        }
        Method area = currentWorkArea;
        if (area != null) {
            try {
                Object workArea = area.invoke(worker);
                if (workArea != null) {
                    workArea.getClass().getMethod("setBeingWorkedOn", boolean.class).invoke(workArea, false);
                }
            } catch (Throwable t) {
                currentWorkArea = null;
                retire("getCurrentWorkArea/setBeingWorkedOn", t);
            }
        }
        Method home = releaseHomeArea;
        if (home != null) {
            try {
                home.invoke(worker);
            } catch (Throwable t) {
                releaseHomeArea = null;
                retire("releaseHomeArea", t);
            }
        }
    }

    public static List<String> describeBindings() {
        List<String> lines = new ArrayList<>();
        lines.add("AbstractWorkerEntity: " + (workerClass != null ? "ok" : "missing"));
        lines.add("isWorking: " + (isWorking != null ? "ok" : "missing"));
        lines.add("work and home area release: " + (currentWorkArea != null && releaseHomeArea != null ? "ok" : "missing"));
        return lines;
    }

    public static Map<String, String> failures() {
        return Map.copyOf(FAILURES);
    }

    private static Method typed(String name, Class<?> result) {
        try {
            Method method = workerClass.getMethod(name);
            if (method.getReturnType() != result) {
                throw new NoSuchMethodException(name + " returns " + method.getReturnType().getName());
            }
            return method;
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing(name, t);
            return null;
        }
    }

    private static void warnMissing(String member, Throwable t) {
        if (FAILURES.putIfAbsent(member, t.toString()) == null) {
            McaMobCompat.LOGGER.warn("Villager Workers binding: '{}' is unavailable ({}). The feature that "
                    + "needs it is disabled; everything else keeps working.", member, t.toString());
        }
    }

    private static void retire(String member, Throwable t) {
        if (FAILURES.putIfAbsent(member, "retired: " + t) == null) {
            McaMobCompat.LOGGER.warn("Villager Workers binding: '{}' threw and is not used again this session.",
                    member, t);
        }
    }
}
