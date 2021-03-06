package net.sf.odinms.tools;

import java.util.ArrayList;
import java.util.List;

public final class CollectionUtil {
    /**
     * Static class dummy constructor
     */
    private CollectionUtil() {
    }

    /**
     * Copies <code>count</code> items off of list, starting from the beginning.
     * @param <T> The type of the list.
     * @param list The list to copy from.
     * @param count The number of items to copy.
     * @return The copied list.
     */
    public static <T> List<T> copyFirst(final List<T> list, final int count) {
        final List<T> ret = new ArrayList<>(list.size() < count ? list.size() : count);
        int i = 0;
        for (final T elem : list) {
            ret.add(elem);
            if (i++ > count) {
                return ret;
            }
        }
        return ret;
    }
}
