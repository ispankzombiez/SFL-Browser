package com.sfl.browser.clustering;

import android.util.Log;
import com.sfl.browser.models.FarmItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clusters aging shed notifications by rack type and short time window.
 * Aging/Fermenting/Spice are grouped independently. Within each rack type,
 * items that complete within 2 minutes are combined into one notification.
 */
public class AgingShedClusterer extends CategoryClusterer {
    private static final String TAG = "AgingShedClusterer";
    private static final long CLUSTERING_WINDOW = 2 * 60 * 1000; // 2 minutes

    @Override
    public List<NotificationGroup> cluster(List<FarmItem> items) {
        Log.d(TAG, "Clustering " + (items != null ? items.size() : 0) + " aging shed item(s)");
        List<NotificationGroup> groups = new ArrayList<>();

        if (items == null || items.isEmpty()) {
            return groups;
        }

        // Split by rack label first so Aging/Fermenting/Spice notify separately.
        Map<String, List<FarmItem>> itemsByRack = new LinkedHashMap<>();
        for (FarmItem item : items) {
            String rackLabel = (item.getDetails() != null && !item.getDetails().trim().isEmpty())
                ? item.getDetails().trim()
                : "Aging";

            List<FarmItem> rackItems = itemsByRack.get(rackLabel);
            if (rackItems == null) {
                rackItems = new ArrayList<>();
                itemsByRack.put(rackLabel, rackItems);
            }
            rackItems.add(item);
        }

        for (Map.Entry<String, List<FarmItem>> entry : itemsByRack.entrySet()) {
            String rackLabel = entry.getKey();
            List<FarmItem> rackItems = entry.getValue();

            rackItems.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

            List<FarmItem> currentCluster = new ArrayList<>();
            long clusterStartTime = 0L;

            for (FarmItem item : rackItems) {
                if (currentCluster.isEmpty()) {
                    currentCluster.add(item);
                    clusterStartTime = item.getTimestamp();
                    continue;
                }

                long timeDifference = item.getTimestamp() - clusterStartTime;
                if (timeDifference <= CLUSTERING_WINDOW) {
                    currentCluster.add(item);
                } else {
                    groups.add(createGroupFromCluster(rackLabel, currentCluster));
                    currentCluster = new ArrayList<>();
                    currentCluster.add(item);
                    clusterStartTime = item.getTimestamp();
                }
            }

            if (!currentCluster.isEmpty()) {
                groups.add(createGroupFromCluster(rackLabel, currentCluster));
            }
        }

        Log.d(TAG, "Created " + groups.size() + " aging shed group(s)");
        return groups;
    }

    private NotificationGroup createGroupFromCluster(String rackLabel, List<FarmItem> cluster) {
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        long earliestReadyTime = Long.MAX_VALUE;

        for (FarmItem item : cluster) {
            String itemName = item.getName();
            itemCounts.put(itemName, itemCounts.getOrDefault(itemName, 0) + 1);
            earliestReadyTime = Math.min(earliestReadyTime, item.getTimestamp());
        }

        String aggregatedItems = buildAggregatedItemsText(itemCounts);

        NotificationGroup group = new NotificationGroup(
            "aging_shed",
            rackLabel,
            cluster.size(),
            earliestReadyTime
        );
        // details holds aggregated item counts for body text.
        group.details = aggregatedItems;
        group.groupId = generateGroupId(rackLabel, earliestReadyTime, aggregatedItems);
        return group;
    }

    private String buildAggregatedItemsText(Map<String, Integer> itemCounts) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            i++;
        }
        return sb.toString();
    }

    private String generateGroupId(String rackLabel, long earliestReadyTime, String aggregatedItems) {
        long timeBucket = (earliestReadyTime / CLUSTERING_WINDOW) * CLUSTERING_WINDOW;
        int detailsHash = aggregatedItems != null ? Math.abs(aggregatedItems.hashCode()) : 0;
        return "agingshed_" + rackLabel.toLowerCase() + "_" + timeBucket + "_" + detailsHash;
    }
}
