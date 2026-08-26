package com.akylas.enforcedoze;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Backs the package chooser list. Holds the full set of installed apps plus the currently visible
 * (filtered) subset, so filtering never loses the selection made before the filter was typed.
 */
public class PackageChooserAdapter extends RecyclerView.Adapter<PackageChooserAdapter.ViewHolder> {

    /** One row: an installed app, whether it is picked, and whether it is already in the target list. */
    public static class Item {
        final String packageName;
        final String label;
        final ApplicationInfo applicationInfo;
        /** True for apps shipped with the ROM (and not since replaced by a user-installed update). */
        final boolean systemApp;
        boolean selected;
        /** Already present in the list we are adding to - shown ticked and not togglable. */
        boolean locked;
        /**
         * Resolved on first bind and kept. Eagerly loading icons for every installed package - and
         * a One UI device has several hundred - would cost far more memory and time than the
         * handful of rows actually on screen.
         */
        private Drawable icon;

        Item(String packageName, String label, ApplicationInfo applicationInfo, boolean systemApp, boolean locked) {
            this.packageName = packageName;
            this.label = label;
            this.applicationInfo = applicationInfo;
            this.systemApp = systemApp;
            this.locked = locked;
            this.selected = locked;
        }

        public String getPackageName() {
            return packageName;
        }

        Drawable getIcon(PackageManager pm) {
            if (icon == null) {
                try {
                    icon = applicationInfo.loadIcon(pm);
                } catch (Exception e) {
                    icon = null;
                }
            }
            return icon;
        }
    }

    public interface OnSelectionChanged {
        void onSelectionChanged(int selectedCount);
    }

    public interface OnItemClicked {
        void onItemClicked(Item item);
    }

    private final List<Item> allItems = new ArrayList<>();
    private final List<Item> visibleItems = new ArrayList<>();
    private final boolean multiSelect;
    private final String alreadyAddedLabel;
    private final PackageManager packageManager;
    private OnSelectionChanged onSelectionChanged;
    private OnItemClicked onItemClicked;
    private String currentQuery = "";
    private boolean showSystemApps = false;

    public PackageChooserAdapter(boolean multiSelect, String alreadyAddedLabel, PackageManager packageManager) {
        this.multiSelect = multiSelect;
        this.alreadyAddedLabel = alreadyAddedLabel;
        this.packageManager = packageManager;
    }

    public void setOnSelectionChanged(OnSelectionChanged listener) {
        this.onSelectionChanged = listener;
    }

    public void setOnItemClicked(OnItemClicked listener) {
        this.onItemClicked = listener;
    }

    public void submit(List<Item> items) {
        allItems.clear();
        allItems.addAll(items);
        applyFilters();
        notifySelectionChanged();
    }

    /** Matches on both the app label and the package name, case-insensitively. */
    public void filter(String query) {
        currentQuery = query == null ? "" : query;
        applyFilters();
    }

    public void setShowSystemApps(boolean showSystemApps) {
        this.showSystemApps = showSystemApps;
        applyFilters();
    }

    public boolean isShowingSystemApps() {
        return showSystemApps;
    }

    /**
     * Rebuilds the visible list from the search text and the system-app switch. Selection lives on
     * the items themselves, so filtering never discards what the user has already ticked.
     */
    private void applyFilters() {
        String needle = currentQuery.trim().toLowerCase(Locale.getDefault());
        visibleItems.clear();
        for (Item item : allItems) {
            // An app already in the target list stays visible whatever the filter says, so the
            // user can always see why it is not offered again.
            if (item.systemApp && !showSystemApps && !item.locked) {
                continue;
            }
            if (!needle.isEmpty()
                    && !item.label.toLowerCase(Locale.getDefault()).contains(needle)
                    && !item.packageName.toLowerCase(Locale.getDefault()).contains(needle)) {
                continue;
            }
            visibleItems.add(item);
        }
        notifyDataSetChanged();
    }

    /**
     * Applies to the visible rows only, so "select all" while a filter is active does what the user
     * sees rather than silently picking up hundreds of hidden apps.
     */
    public void setAllVisibleSelected(boolean selected) {
        for (Item item : visibleItems) {
            if (!item.locked) {
                item.selected = selected;
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    /** True when every selectable visible row is already picked. */
    public boolean areAllVisibleSelected() {
        boolean sawSelectable = false;
        for (Item item : visibleItems) {
            if (!item.locked) {
                sawSelectable = true;
                if (!item.selected) {
                    return false;
                }
            }
        }
        return sawSelectable;
    }

    /** Newly picked packages only - the ones already in the list are never returned again. */
    public ArrayList<String> getNewlySelectedPackages() {
        ArrayList<String> result = new ArrayList<>();
        for (Item item : allItems) {
            if (item.selected && !item.locked) {
                result.add(item.packageName);
            }
        }
        return result;
    }

    public int getNewlySelectedCount() {
        return getNewlySelectedPackages().size();
    }

    private void notifySelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.onSelectionChanged(getNewlySelectedCount());
        }
    }

    @Override
    public int getItemCount() {
        return visibleItems.size();
    }

    public Item getItem(int position) {
        return visibleItems.get(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.package_chooser_row_selectable, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = getItem(position);
        holder.label.setText(item.label);
        holder.packageName.setText(item.packageName);
        holder.icon.setImageDrawable(item.getIcon(packageManager));

        if (multiSelect) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(item.selected);
            holder.checkBox.setEnabled(!item.locked);
        } else {
            holder.checkBox.setVisibility(View.GONE);
        }

        holder.status.setVisibility(item.locked ? View.VISIBLE : View.GONE);
        holder.status.setText(alreadyAddedLabel);
        holder.itemView.setEnabled(!item.locked);
        holder.itemView.setAlpha(item.locked ? 0.6f : 1f);

        holder.itemView.setOnClickListener(v -> {
            if (item.locked) {
                return;
            }
            if (!multiSelect) {
                if (onItemClicked != null) {
                    onItemClicked.onItemClicked(item);
                }
                return;
            }
            item.selected = !item.selected;
            // Updating the one view beats notifyItemChanged here: no rebind, no position lookup,
            // and no flicker from the default change animation.
            holder.checkBox.setChecked(item.selected);
            notifySelectionChanged();
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final TextView status;
        final MaterialCheckBox checkBox;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            label = itemView.findViewById(R.id.label);
            packageName = itemView.findViewById(R.id.packageName);
            status = itemView.findViewById(R.id.status);
            checkBox = itemView.findViewById(R.id.checkbox);
        }
    }
}
