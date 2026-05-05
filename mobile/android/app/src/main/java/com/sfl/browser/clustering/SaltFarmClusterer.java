package com.sfl.browser.clustering;

import android.util.Log;
import com.sfl.browser.models.FarmItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Clusters salt farm notifications by time window.
 * Groups salt nodes that are ready within 5 minutes of each other
 * into a single notification.
 */
public class SaltFarmClusterer extends CategoryClusterer {
    private static final String TAG = "SaltFarmClusterer";
    private static final long CLUSTERING_WINDOW = 5 * 60 * 1000; // 5 minutes

    @Override
    public List<NotificationGroup> cluster(List<FarmItem> items) {
        Log.d(TAG, "Clustering " + (items != null ? items.size() : 0) + " salt node(s)");
        List<NotificationGroup> groups = new ArrayList<>();

        if (items == null || items.isEmpty()) {
            return groups;
        }

        // Sort by timestamp
        items.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        List<FarmItem> currentCluster = new ArrayList<>();
        long clusterStartTime = 0L;

        for (FarmItem item : items) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(item);
                clusterStartTime = item.getTimestamp();
                continue;
            }

            long timeDifference = item.getTimestamp() - clusterStartTime;
            if (timeDifference <= CLUSTERING_WINDOW) {
                currentCluster.add(item);
            } else {
                // Flush current cluster
                groups.add(createGroupFromCluster(currentCluster));
                // Start new cluster
                currentCluster = new ArrayList<>();
                currentCluster.add(item);
                clusterStartTime = item.getTimestamp();
            }
        }

        // Flush last cluster
        if (!currentCluster.isEmpty()) {
            groups.add(createGroupFromCluster(currentCluster));
        }

        Log.d(TAG, "Created " + groups.size() + " salt farm group(s)");
        return groups;
    }

    private NotificationGroup createGroupFromCluster(List<FarmItem> cluster) {
        long earliestReadyTime = cluster.get(0).getTimestamp();
        int quantity = cluster.size();

        NotificationGroup group = new NotificationGroup(
            "salt_farm",
            "Salt node",
            quantity,
            earliestReadyTime
        );
        group.groupId = generateGroupId();
        return group;
    }

    private String generateGroupId() {
        return "saltfarm_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }
}
