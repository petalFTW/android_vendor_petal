package org.petal.catback.car;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

// feeds the island, maps guidance and media
public final class PetalNotificationListener extends NotificationListenerService {
    private static volatile PetalNotificationListener active;

    // whatever's already in the shade goes on the car. a session starting calls this too, and if the system
    // hasn't bound us (fresh install, process got killed) asks it to, the connect callback scans when it lands
    public static void rescan(android.content.Context context) {
        // listener callbacks (and CarData's notif maps) live on the main thread, sessions start on their own
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> rescan(context));
            return;
        }
        if (active == null) {
            try { requestRebind(new android.content.ComponentName(context, PetalNotificationListener.class)); }
            catch (RuntimeException notAllowed) { }
            return;
        }
        rescan();
    }
    static void rescan() {
        PetalNotificationListener listener = active;
        if (listener == null) return;
        StatusBarNotification[] all;
        RankingMap ranking;
        try { all = listener.getActiveNotifications(); ranking = listener.getCurrentRanking(); }
        catch (RuntimeException e) { android.util.Log.w("Catback", "Couldn't read the shade", e); return; }
        if (all == null) return;
        CarData data = CarData.get(listener);
        data.resetShowing();
        // old notifs aren't news, just remember em. one at a time: a single bad one (icon from a package we can't
        // read, say) used to throw out of the loop and leave every notif after it off the car
        for (StatusBarNotification sbn : all) {
            try { data.seed(sbn, importance(ranking, sbn)); }
            catch (RuntimeException e) { android.util.Log.w("Catback", "Skipped notification " + sbn.getKey(), e); }
        }
    }

    @Override public void onListenerConnected() {
        active = this;
        CarData.get(this).listener(true);
        rescan();
    }
    @Override public void onListenerDisconnected() {
        active = null;
        CarData.get(this).listener(false);
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap ranking) {
        // importance decides it, island and carousel both only take alerting ones (DEFAULT+), silent never reaches the car
        CarData.get(this).posted(sbn, importance(ranking, sbn));
    }
    private static int importance(RankingMap ranking, StatusBarNotification sbn) {
        Ranking r = new Ranking();
        return ranking != null && ranking.getRanking(sbn.getKey(), r) ? r.getImportance() : android.app.NotificationManager.IMPORTANCE_MIN;
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { CarData.get(this).removed(sbn); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking) { CarData.get(this).removed(sbn); }
}
