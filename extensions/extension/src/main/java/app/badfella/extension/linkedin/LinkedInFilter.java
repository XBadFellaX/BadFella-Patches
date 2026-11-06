package app.badfella.extension.linkedin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime filters for the reviewed LinkedIn 4.1.1271 feed and bottom navigation.
 *
 * The APK's Pegasus models and tab definitions keep their field names, so the filters read the
 * update's metadata and content components directly. Every lookup fails safe: a missing field or
 * class returns "not filtered" instead of throwing into the feed.
 */
@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public final class LinkedInFilter {

    private static final String[] HIDEABLE_TABS = {
            "RELATIONSHIPS", "POST", "NOTIFICATIONS", "JOBS", "VIDEO", "NEWS",
    };

    // ---- Feed -------------------------------------------------------------------------------

    /** Sponsored/promoted post that is neither a job card nor a suggested/promotion carousel. */
    public static boolean isSponsoredPost(Object update) {
        if (!isSponsored(update)) return false;
        if (hasComponent(update, "hasJobComponentValue")) return false;
        if (hasComponent(update, "hasFollowPromptComponentValue")) return false;
        if (field(update, "carouselContent") != null) return false;
        if (hasComponent(update, "hasFeedDiscoveryEntityComponentValue")) return false;
        if (hasComponent(update, "hasFeedDiscoveryGridComponentValue")) return false;
        if (hasComponent(update, "hasEntityComponentValue")) return false;
        return true;
    }

    /** Sponsored job card and promoted job recommendation. */
    public static boolean isPromotedJob(Object update) {
        return isSponsored(update) && hasComponent(update, "hasJobComponentValue");
    }

    /** Recommended follows and sponsored discovery/pages carousels. */
    public static boolean isSuggestedCarousel(Object update) {
        if (update == null) return false;
        if (hasComponent(update, "hasFollowPromptComponentValue")) return true;
        if (!isSponsored(update)) return false;
        if (field(update, "carouselContent") != null) return true;
        return hasComponent(update, "hasFeedDiscoveryEntityComponentValue")
                || hasComponent(update, "hasFeedDiscoveryGridComponentValue")
                || hasComponent(update, "hasEntityComponentValue");
    }

    /** Any feed update whose content or attachment is a video component. */
    public static boolean isVideoPost(Object update) {
        return hasComponent(update, "hasLinkedInVideoComponentValue")
                || hasComponent(update, "hasLargeSingleVideoComponentValue")
                || hasComponent(update, "hasExternalVideoComponentValue");
    }

    private static boolean isSponsored(Object update) {
        Object metadata = field(update, "metadata");
        Object tracking = field(metadata, "trackingData");
        return field(tracking, "sponsoredTracking") != null;
    }

    private static boolean hasComponent(Object update, String flag) {
        if (update == null) return false;
        if (isTrue(field(update, "content"), flag)) return true;
        Object attachment = field(update, "attachment");
        return isTrue(field(attachment, "content"), flag);
    }

    private static boolean isTrue(Object component, String flag) {
        if (component == null) return false;
        Object value = field(component, flag);
        return value instanceof Boolean && (Boolean) value;
    }

    // ---- Bottom navigation ------------------------------------------------------------------

    /**
     * Drops the bottom-bar tabs selected by {@code mask}. Returns a new list; an unknown class,
     * field or reflection failure leaves the original list untouched.
     */
    public static List filterTabs(List list, int mask) {
        if (list == null || mask == 0 || list.isEmpty()) return list;
        try {
            Object first = null;
            for (Object item : list) {
                if (item != null) { first = item; break; }
            }
            if (first == null) return list;
            Set<Integer> hidden = new HashSet<>();
            for (int i = 0; i < HIDEABLE_TABS.length; i++) {
                if ((mask & (1 << i)) == 0) continue;
                Object tab = staticField(first.getClass(), HIDEABLE_TABS[i]);
                if (tab != null) hidden.add(readInt(tab, "id"));
            }
            if (hidden.isEmpty()) return list;
            ArrayList retained = new ArrayList(list.size());
            for (Object item : list) {
                if (item == null || hidden.contains(readInt(item, "id"))) continue;
                retained.add(item);
            }
            return retained;
        } catch (Throwable ignored) {
            return list;
        }
    }

    // ---- Reflection -------------------------------------------------------------------------

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try {
            return findField(owner.getClass(), name).get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object staticField(Class<?> type, String name) {
        try {
            return findField(type, name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readInt(Object owner, String name) throws NoSuchFieldException, IllegalAccessException {
        return findField(owner.getClass(), name).getInt(owner);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private LinkedInFilter() {
    }
}
