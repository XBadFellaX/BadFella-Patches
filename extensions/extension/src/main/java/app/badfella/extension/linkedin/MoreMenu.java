package app.badfella.extension.linkedin;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets each item of LinkedIn's "More" tab (Puzzle games, Groups, Events, Newsletters, ...) be hidden, and
 * optionally moves the items to the bottom of the native "Me" panel, above "Settings & Privacy", removing the tab.
 *
 * The items differ by region, so the ones the server sends on the More screen are remembered; until that
 * screen has been seen, the items known from 4.1.1264 are used.
 */
@SuppressWarnings("unused")
public final class MoreMenu {
    static final String MORE_SCREEN = "com.linkedin.sdui.impl.homenav.moreScreenContent";
    /** Tracking name of each item on the More screen, followed by its id. */
    private static final String ITEM_PREFIX = "nav-more-";
    private static final String LINK_PREFIX = "https://www.linkedin.com/";

    /** One More item: id from its tracking name, label and destination. */
    static final class Item {
        final String id;
        final String label;
        final String url;

        Item(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }

        /** Preference key of the item's switch. */
        String key() {
            return Settings.MORE_ITEM_PREFIX + id;
        }
    }

    private static final Item[] DEFAULT_ITEMS = {
            new Item("games", "Puzzle games", "https://www.linkedin.com/games/"),
            new Item("groups", "Groups", "https://www.linkedin.com/groups/"),
            new Item("events", "Events", "https://www.linkedin.com/events/"),
            new Item("newsletters", "Newsletters", "https://www.linkedin.com/mynetwork/network-manager/newsletters/"),
    };

    private static final class SavedStyle {
        final int marginStart, marginEnd, topMargin, bottomMargin, width;
        final int padLeft, padTop, padRight, padBottom;
        final Drawable background;
        final int textColor;
        final Typeface typeface;
        final int chevronVisibility;

        SavedStyle(View root, ViewGroup.MarginLayoutParams params, TextView title, View chevron) {
            this.marginStart = params != null ? params.getMarginStart() : 0;
            this.marginEnd = params != null ? params.getMarginEnd() : 0;
            this.topMargin = params != null ? params.topMargin : 0;
            this.bottomMargin = params != null ? params.bottomMargin : 0;
            this.width = params != null ? params.width : ViewGroup.LayoutParams.MATCH_PARENT;
            this.padLeft = root.getPaddingLeft();
            this.padTop = root.getPaddingTop();
            this.padRight = root.getPaddingRight();
            this.padBottom = root.getPaddingBottom();
            this.background = root.getBackground();
            this.textColor = title != null ? title.getCurrentTextColor() : 0;
            this.typeface = title != null ? title.getTypeface() : null;
            this.chevronVisibility = chevron != null ? chevron.getVisibility() : View.VISIBLE;
        }
    }

    private static final Map<View, int[]> ORIGINAL_MARGINS = new WeakHashMap<>();
    private static final Map<View, SavedStyle> SAVED_PILL_STYLES = new WeakHashMap<>();

    static final Item PATCHES = new Item("badfella-patches", "BadFella Patches", null);

    private MoreMenu() {
    }

    /** The More items: the ones last seen on the More screen, or the defaults. */
    static List<Item> items() {
        List<Item> items = new ArrayList<>();
        try {
            String saved = Settings.getString(Settings.MORE_ITEMS, null);
            if (saved != null) {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    items.add(new Item(o.getString("id"), o.getString("label"), o.getString("url")));
                }
            }
        } catch (Exception e) {
            Diagnostics.error("more items unreadable", e);
            items.clear();
        }
        if (items.isEmpty()) Collections.addAll(items, DEFAULT_ITEMS);
        return items;
    }

    /** More-tab shortcut cards shown above the footer divider. */
    static List<Item> shownItems() {
        List<Item> shown = new ArrayList<>();
        if (Settings.moveMoreItems()) {
            for (Item item : items()) if (Settings.get(item.key(), true)) shown.add(item);
        }
        return shown;
    }

    /** Called with the view name of every SDUI component: true for a hidden item row of the More screen. */
    static boolean hideItem(String viewName) {
        if (!Settings.moreMenu() || !viewName.startsWith(ITEM_PREFIX)) return false;
        return !Settings.get(Settings.MORE_ITEM_PREFIX + viewName.substring(ITEM_PREFIX.length()), true);
    }

    // region More screen

    /**
     * Called with the More screen component: remembers its items. Each item carries its link,
     * then its tracking name (nav-more-<id>), then its label.
     */
    static void capture(byte[] payload) {
        if (!Settings.isMoreMenuIncluded() || payload == null) return;
        List<String> strings = ProtoScanner.scan(payload).strings;
        JSONArray array = new JSONArray();
        String url = null;
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);
            if (s.startsWith(LINK_PREFIX)) url = s;
            if (!s.startsWith(ITEM_PREFIX) || url == null) continue;
            String label = null;
            for (int j = i + 1; j < strings.size() && label == null; j++) {
                String next = strings.get(j);
                if (!next.isEmpty() && !next.endsWith("==") && !next.startsWith(ITEM_PREFIX)) label = next;
            }
            if (label == null) continue;
            try {
                array.put(new JSONObject().put("id", s.substring(ITEM_PREFIX.length()))
                        .put("label", label).put("url", url));
            } catch (Exception ignored) {
            }
            url = null;
        }
        if (array.length() == 0) return;
        String json = array.toString();
        if (!json.equals(Settings.getString(Settings.MORE_ITEMS, null))) {
            Settings.setString(Settings.MORE_ITEMS, json);
            Settings.debugLog("more items: " + json);
        }
    }

    // endregion

    // region Me panel

    private static final String PACKAGE = "com.linkedin.android.home.navpanel.";

    /**
     * Injected after HomeNavPanelFeature puts the divider in the list at the bottom of the Me panel (the divider,
     * then "Settings & Privacy"): adds shortcut cards above the divider and the BadFella Patches button at the bottom.
     */
    public static void addToFooter(Object list) {
        if (list == null) return;
        List<Item> shown = shownItems();
        try {
            Class<?> viewData = Class.forName(PACKAGE + "HomeNavPanelSectionV2ViewData");
            Object savedPosts = Class.forName(PACKAGE + "SectionType").getField("SAVED_POSTS").get(null);
            Object chevronRight = Class.forName(PACKAGE + "HomeNavPanelSectionChevronType").getField("RIGHT").get(null);
            Method addItem = list.getClass().getMethod("addItem", int.class, Object.class);
            for (int i = 0; i < shown.size(); i++) {
                addItem.invoke(list, i, newSection(viewData, shown.get(i).label, savedPosts, chevronRight));
            }
            Object patchesSection = newSection(viewData, PATCHES.label, savedPosts, chevronRight);
            appendItem(list, addItem, patchesSection, shown.size());
        } catch (Throwable t) {
            Diagnostics.error("addToFooter failed", t);
        }
    }

    private static void appendItem(Object list, Method addItemAt, Object item, int fallbackIndex) throws Exception {
        try {
            Method append = list.getClass().getMethod("addItem", Object.class);
            append.invoke(list, item);
            return;
        } catch (NoSuchMethodException ignored) {
        }
        int end = footerSize(list, fallbackIndex);
        addItemAt.invoke(list, end, item);
    }

    private static int footerSize(Object list, int fallback) {
        for (String name : new String[]{"getItemCount", "size"}) {
            try {
                Object result = list.getClass().getMethod(name).invoke(list);
                if (result instanceof Integer) return (Integer) result;
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    /**
     * Builds a section through whichever constructor this LinkedIn build has: a String title, SectionType and
     * ChevronType, followed by lists, ints and booleans that are left empty.
     */
    private static Object newSection(Class<?> viewData, String title, Object sectionType, Object chevron)
            throws Exception {
        for (Constructor<?> constructor : viewData.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 3 || !types[0].isAssignableFrom(String.class)
                    || !types[1].isInstance(sectionType) || !types[2].isInstance(chevron)) continue;
            Object[] args = new Object[types.length];
            args[0] = title;
            args[1] = sectionType;
            args[2] = chevron;
            for (int i = 3; i < types.length; i++) {
                if (types[i] == List.class) args[i] = Collections.emptyList();
                else if (types[i] == int.class) args[i] = 0;
                else if (types[i] == boolean.class) args[i] = false;
            }
            return constructor.newInstance(args);
        }
        throw new NoSuchMethodException(viewData.getName() + ".<init>(title, SectionType, ChevronType, ...)");
    }

    /**
     * Injected before every return of HomeNavPanelSectionV2Presenter.onBind(ViewDataBinding): opens the item, and
     * puts its icon before the title, since the panel's sections have no icon of their own.
     */
    public static void onSectionBind(Object presenter, Object binding) {
        try {
            Object viewData = Reflect.get(presenter, "viewData");
            String title = String.valueOf(Reflect.get(viewData, "title"));
            Object root = binding.getClass().getMethod("getRoot").invoke(binding);
            Item match = null;
            if (PATCHES.label.equals(title)) match = PATCHES;
            else for (Item item : shownItems()) if (item.label.equals(title)) match = item;
            // Section views are recycled, so a view that showed a styled button or card restores its original look.
            if (root instanceof View) {
                View rootView = (View) root;
                if (match == PATCHES) {
                    stylePatchesButton(rootView);
                } else {
                    restorePatchesButton(rootView);
                    setIcon(rootView, match);
                    setMargins(rootView, match != null);
                }
            }
            if (match == null) return;
            Item item = match;
            View.OnClickListener open = v -> {
                if (item == PATCHES) SettingsActivity.open(activityOf(v.getContext()));
                else open(v.getContext(), item.url);
            };
            Field field = Reflect.field(presenter.getClass(), "sectionHeaderClickListener");
            if (field != null) field.set(presenter, open);
        } catch (Throwable t) {
            Diagnostics.error("onSectionBind failed", t);
        }
    }

    private static void stylePatchesButton(View root) {
        TextView title = findTitle(root);
        View chevron = findView(root, "chevron");
        ViewGroup.MarginLayoutParams params = root.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? (ViewGroup.MarginLayoutParams) root.getLayoutParams() : null;
        if (!SAVED_PILL_STYLES.containsKey(root)) {
            SAVED_PILL_STYLES.put(root, new SavedStyle(root, params, title, chevron));
        }
        float density = root.getResources().getDisplayMetrics().density;
        boolean dark = (root.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int accent = MaterialYou.settingsAccent(dark,
                dark ? SettingsActivity.ACCENT_DARK : SettingsActivity.ACCENT_LIGHT);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor((accent & 0x00FFFFFF) | (dark ? 0x2E000000 : 0x1F000000));
        pill.setCornerRadius(24f * density);
        pill.setStroke(Math.max(1, Math.round(density)), (accent & 0x00FFFFFF) | 0x4D000000);
        root.setBackground(pill);
        root.setPadding(Math.round(16 * density), Math.round(10 * density),
                Math.round(18 * density), Math.round(10 * density));
        if (params != null) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            int side = Math.round(17 * density);
            params.setMarginStart(side);
            params.setMarginEnd(side);
            params.topMargin = Math.round(8 * density);
            params.bottomMargin = Math.round(16 * density);
            root.setLayoutParams(params);
        }
        if (chevron != null) chevron.setVisibility(View.GONE);
        if (title != null) {
            IconDrawable icon = new IconDrawable(IconDrawable.APPS, accent);
            int size = Math.round(20 * density);
            icon.setBounds(0, 0, size, size);
            title.setCompoundDrawablesRelative(icon, null, null, null);
            title.setCompoundDrawablePadding(Math.round(10 * density));
            title.setTextColor(accent);
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    private static void restorePatchesButton(View root) {
        SavedStyle saved = SAVED_PILL_STYLES.remove(root);
        if (saved == null) return;
        root.setBackground(saved.background);
        root.setPadding(saved.padLeft, saved.padTop, saved.padRight, saved.padBottom);
        if (root.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
            params.width = saved.width;
            params.setMarginStart(saved.marginStart);
            params.setMarginEnd(saved.marginEnd);
            params.topMargin = saved.topMargin;
            params.bottomMargin = saved.bottomMargin;
            root.setLayoutParams(params);
        }
        View chevron = findView(root, "chevron");
        if (chevron != null) chevron.setVisibility(saved.chevronVisibility);
        TextView title = findTitle(root);
        if (title != null) {
            title.setTextColor(saved.textColor);
            title.setTypeface(saved.typeface);
            if (title.getCompoundDrawablesRelative()[0] instanceof IconDrawable) {
                title.setCompoundDrawablesRelative(null, null, null, null);
            }
        }
    }

    private static TextView findTitle(View root) {
        View view = findView(root, "title");
        return view instanceof TextView ? (TextView) view : null;
    }

    private static View findView(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    /** Draws the item's icon at the start of its title, in the title's color; null removes an icon set before. */
    private static void setIcon(View root, Item item) {
        TextView title = findTitle(root);
        if (title == null) return;
        if (item == null) {
            if (title.getCompoundDrawablesRelative()[0] instanceof IconDrawable) {
                title.setCompoundDrawablesRelative(null, null, null, null);
            }
            return;
        }
        float density = root.getResources().getDisplayMetrics().density;
        IconDrawable icon = new IconDrawable(icon(item.id), title.getCurrentTextColor());
        icon.setBounds(0, 0, (int) (24 * density), (int) (24 * density));
        title.setCompoundDrawablesRelative(icon, null, null, null);
        title.setCompoundDrawablePadding((int) (16 * density));
    }

    /**
     * The footer list has no spacing of its own, unlike the list above it, so the item cards get the margins of the
     * cards there ("Saved posts"): 17dp at the sides and 16dp between cards.
     */
    private static void setMargins(View root, boolean card) {
        if (!(root.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
        int[] original = ORIGINAL_MARGINS.get(root);
        if (!card) {
            if (original == null) return;
            params.setMarginStart(original[0]);
            params.setMarginEnd(original[1]);
            params.bottomMargin = original[2];
            root.setLayoutParams(params);
            ORIGINAL_MARGINS.remove(root);
            return;
        }
        if (original == null) {
            ORIGINAL_MARGINS.put(root, new int[] {
                    params.getMarginStart(), params.getMarginEnd(), params.bottomMargin});
        }
        float density = root.getResources().getDisplayMetrics().density;
        int side = Math.round(17 * density);
        params.setMarginStart(side);
        params.setMarginEnd(side);
        params.bottomMargin = Math.round(16 * density);
        root.setLayoutParams(params);
    }

    private static String icon(String id) {
        switch (id) {
            case "badfella-patches":
                return IconDrawable.APPS;
            case "games":
                return IconDrawable.PUZZLE;
            case "groups":
                return IconDrawable.GROUPS;
            case "events":
                return IconDrawable.EVENTS;
            case "newsletters":
                return IconDrawable.ARTICLE;
            default:
                return IconDrawable.APPS;
        }
    }

    private static Context activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return context;
    }

    /** Opens a linkedin.com link inside LinkedIn, the way its own deep links are handled. */
    private static void open(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(context.getPackageName());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // endregion
}
