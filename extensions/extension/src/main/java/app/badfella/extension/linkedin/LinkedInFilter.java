package app.badfella.extension.linkedin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    /** Bound for reshared/attachment/carousel wrapping so a malformed graph cannot recurse. */
    private static final int MAX_UPDATE_DEPTH = 3;

    // ---- Feed -------------------------------------------------------------------------------

    /** Sponsored/promoted post that is not a promoted job (that has its own selection). */
    public static boolean isSponsoredPost(Object update) {
        if (!isSponsored(update)) return false;
        return !hasComponent(update, "hasJobComponentValue");
    }

    /** Sponsored job card and promoted job recommendation. */
    public static boolean isPromotedJob(Object update) {
        return isSponsored(update) && hasComponent(update, "hasJobComponentValue");
    }

    /** Recommended follows and sponsored discovery/entity/carousel rows, including video carousels. */
    public static boolean isSuggestedCarousel(Object update) {
        if (update == null) return false;
        if (hasComponent(update, "hasFollowPromptComponentValue")) return true;
        if (isVideoCarousel(update)) return true;
        if (!isSponsored(update)) return false;
        if (hasCarouselContent(update)) return true;
        return hasComponent(update, "hasFeedDiscoveryEntityComponentValue")
                || hasComponent(update, "hasFeedDiscoveryGridComponentValue")
                || hasComponent(update, "hasEntityComponentValue");
    }

    /** Any feed update whose content or attachment is a video, or whose carousel is a video carousel. */
    public static boolean isVideoPost(Object update) {
        return hasComponent(update, "hasLinkedInVideoComponentValue")
                || hasComponent(update, "hasLargeSingleVideoComponentValue")
                || hasComponent(update, "hasExternalVideoComponentValue")
                || hasComponent(update, "hasVideoCarouselItemComponentValue")
                || isVideoCarousel(update);
    }

    /**
     * Sponsored or promoted content. Sponsored tracking metadata is authoritative; lead-gen form
     * content, boosted headers, call-to-action cards and the feed's Promoted/Sponsored labels cover
     * ads that arrive without it. Ads can also wrap an inner update, so nested updates are checked
     * with a bounded depth.
     */
    private static boolean isSponsored(Object update) {
        return isSponsored(update, 0);
    }

    private static boolean isSponsored(Object update, int depth) {
        if (update == null || depth > MAX_UPDATE_DEPTH) return false;
        Object tracking = field(field(update, "metadata"), "trackingData");
        if (field(tracking, "sponsoredTracking") != null) return true;
        if (isTrue(tracking, "hasSponsoredTracking")) return true;
        if (field(update, "leadGenFormContent") != null) return true;
        if (isTrue(update, "hasLeadGenFormContent")) return true;
        if (isTrue(update, "hasBoostHeader") || field(update, "boostHeader") != null) return true;
        if (hasCallToAction(update)) return true;
        if (hasPromotedLabel(update)) return true;
        if (hasSponsoredCarouselItem(update, depth)) return true;
        if (isSponsored(field(update, "resharedUpdate"), depth + 1)) return true;
        return isSponsored(field(field(update, "attachment"), "update"), depth + 1);
    }

    private static boolean hasCallToAction(Object update) {
        if (hasComponent(update, "hasCallToActionComponentValue")) return true;
        return field(field(update, "content"), "callToActionComponentValue") != null
                || field(field(field(update, "attachment"), "content"),
                        "callToActionComponentValue") != null;
    }

    private static boolean hasPromotedLabel(Object update) {
        return hasPromotedActor(field(update, "actor"))
                || hasPromotedActor(field(field(update, "content"), "actorComponentValue"))
                || hasPromotedTextIn(field(update, "header"))
                || hasPromotedTextIn(field(update, "detailHeader"))
                || hasPromotedTextIn(field(update, "contextualHeader"));
    }

    private static boolean hasPromotedActor(Object actor) {
        if (actor == null) return false;
        return isPromotedText(field(actor, "subDescription"))
                || isPromotedText(field(actor, "subDescriptionButton"))
                || isPromotedText(field(actor, "supplementaryActorInfo"))
                || isPromotedText(field(actor, "description"));
    }

    /** {@code HeaderComponent.text} and the {@code ContextualHeaderComponent} text variants. */
    private static boolean hasPromotedTextIn(Object header) {
        if (header == null) return false;
        return isPromotedText(field(header, "text"))
                || isPromotedText(field(header, "title"))
                || isPromotedText(field(header, "subtitle"))
                || isPromotedText(field(header, "contextualTitle"))
                || isPromotedText(field(header, "titleSupplementaryInfo"))
                || isPromotedText(field(header, "contextualTitleSupplementaryInfo"));
    }

    /**
     * TextViewModel carries both {@code text} and {@code accessibilityText}; ButtonComponent
     * carries a plain string. Labels can be joined with separators or non-breaking spaces, so
     * match the words rather than the whole value.
     */
    private static boolean isPromotedText(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence) return mentionsPromoted(value.toString());
        return mentionsPromoted(asString(field(value, "text")))
                || mentionsPromoted(asString(field(value, "accessibilityText")));
    }

    private static boolean mentionsPromoted(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("promoted") || lower.contains("sponsored");
    }

    private static boolean hasSponsoredCarouselItem(Object update, int depth) {
        Object items = field(carouselOf(update), "items");
        if (!(items instanceof List)) return false;
        for (Object item : (List) items) {
            if (item == null) continue;
            Object itemTracking = field(item, "trackingData");
            if (field(itemTracking, "sponsoredTracking") != null) return true;
            if (isTrue(itemTracking, "hasSponsoredTracking")) return true;
            if (isSponsored(field(item, "update"), depth + 1)) return true;
        }
        return false;
    }

    private static boolean hasCarouselContent(Object update) {
        return carouselOf(update) != null;
    }

    private static Object carouselOf(Object update) {
        Object carousel = field(update, "carouselContent");
        if (carousel == null) carousel = field(field(update, "attachment"), "carouselContent");
        if (carousel == null) carousel = field(field(update, "leadGenFormContent"), "carouselContent");
        return carousel;
    }

    private static boolean isVideoCarousel(Object update) {
        if (update == null) return false;
        Object carousel = carouselOf(update);
        if (carousel == null) return false;
        if (isVideoCarouselType(field(carousel, "type"))) return true;
        Object items = field(carousel, "items");
        if (!(items instanceof List)) return false;
        for (Object item : (List) items) {
            if (item == null) continue;
            Object nested = field(item, "update");
            if (hasComponent(nested, "hasVideoCarouselItemComponentValue")) return true;
        }
        return false;
    }

    private static boolean isVideoCarouselType(Object type) {
        if (type == null) return false;
        String name = type instanceof Enum ? ((Enum) type).name() : asString(type);
        return name != null && name.contains("VIDEO");
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

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
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
