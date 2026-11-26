package app.badfella.extension.linkedin;

import java.util.*;
import com.linkedin.android.home.navpanel.*;

public class LinkedInPanelCheck {
    public static class Footer {
        final List<Object> items = new ArrayList<>(List.of("divider", "privacy"));
        public void addItem(int index, Object item) { items.add(index, item); }
        public void addItem(Object item) { items.add(item); }
    }
    public static class Presenter {
        Object viewData;
        android.view.View.OnClickListener sectionHeaderClickListener;
    }
    public static class Binding {
        final android.view.View root = new android.view.View();
        public android.view.View getRoot() { return root; }
    }
    static void order(String expected) {
        String actual = String.join(",", MoreMenu.shownItems().stream().map(i -> i.id).toList());
        if (!expected.equals(actual)) throw new AssertionError(actual);
    }
    public static void main(String[] args) {
        order("games,groups,events,newsletters");
        Footer footer = new Footer();
        MoreMenu.addToFooter(footer);
        if (footer.items.size() != 7 || !"divider".equals(footer.items.get(4))
                || !"privacy".equals(footer.items.get(5))) throw new AssertionError("Footer order changed");
        if (!"BadFella Patches".equals(((HomeNavPanelSectionV2ViewData) footer.items.get(6)).title))
            throw new AssertionError("Settings button must be at the bottom of the footer");
        Presenter presenter = new Presenter();
        presenter.viewData = footer.items.get(6);
        Binding binding = new Binding();
        binding.root.title = new android.widget.TextView();
        binding.root.chevron = new android.view.View();
        android.app.Activity activity = new android.app.Activity();
        binding.root.context = new android.content.ContextWrapper(activity);
        MoreMenu.onSectionBind(presenter, binding);
        android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) binding.root.params;
        if (margins.width != android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                || !(binding.root.background instanceof android.graphics.drawable.GradientDrawable)
                || binding.root.chevron.visibility != android.view.View.GONE
                || binding.root.title.textColor != 0xFF1A73E8
                || !(binding.root.title.getCompoundDrawablesRelative()[0] instanceof IconDrawable)
                || ((IconDrawable) binding.root.title.getCompoundDrawablesRelative()[0]).color != 0xFF1A73E8)
            throw new AssertionError("Patches pill styling missing");
        presenter.sectionHeaderClickListener.onClick(binding.root);
        if (SettingsActivity.opened != activity) throw new AssertionError("Settings click lost Activity context");
        presenter.viewData = "native row";
        MoreMenu.onSectionBind(presenter, binding);
        if (margins.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT
                || margins.start != 3 || margins.end != 5 || margins.bottomMargin != 7
                || binding.root.background != null
                || binding.root.chevron.visibility != android.view.View.VISIBLE
                || binding.root.title.textColor != 123
                || binding.root.title.getCompoundDrawablesRelative()[0] != null)
            throw new AssertionError("Recycled native row kept pill styling");
        Settings.hidden = "groups";
        order("games,events,newsletters");
        Settings.hidden = "games";
        order("groups,events,newsletters");
        Settings.moved = false;
        order("");
        Footer settingsOnly = new Footer();
        MoreMenu.addToFooter(settingsOnly);
        if (settingsOnly.items.size() != 3
                || !"BadFella Patches".equals(((HomeNavPanelSectionV2ViewData) settingsOnly.items.get(2)).title))
            throw new AssertionError("Missing bottom settings button without moved More items");
        ArrayList<Object> panel=new ArrayList<>();
        panel.add(new HomeNavPanelSectionV2ViewData("Games",SectionType.GAMES,
                HomeNavPanelSectionChevronType.RIGHT,List.of(),null,List.of(),0,0));
        panel.add("saved posts");
        if(NavPanelPatch.filterPanel(panel)!=panel) throw new AssertionError("Unchanged panel copied");
        Settings.hide=true;
        if(!NavPanelPatch.filterPanel(panel).equals(List.of("saved posts")) || panel.size()!=2)
            throw new AssertionError("Native games filtering must preserve input");
        if(NavPanelPatch.filterPanel(null)!=null) throw new AssertionError("Null panel");
    }
}
