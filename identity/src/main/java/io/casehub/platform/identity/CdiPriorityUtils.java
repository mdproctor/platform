package io.casehub.platform.identity;

import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CdiPriorityUtils {

    private CdiPriorityUtils() {}

    public static <T> List<T> toSortedList(Instance<T> instance) {
        var list = new ArrayList<Instance.Handle<T>>();
        instance.handles().forEach(list::add);
        list.sort(Comparator.comparingInt(CdiPriorityUtils::priorityOf));
        return list.stream()
                .map(Instance.Handle::get)
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    private static int priorityOf(Instance.Handle<?> handle) {
        var bean = handle.getBean();
        if (bean instanceof io.quarkus.arc.InjectableBean<?> injectable) {
            Integer p = injectable.getPriority();
            if (p != null) return p;
        }
        return Integer.MAX_VALUE;
    }
}
