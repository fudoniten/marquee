(ns marquee.pages.home
  "Landing page / control room: a live snapshot of what's on air, what jobs are
  running, and quick links into the rest of the tool."
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content]]))

;;; ── Time helpers ─────────────────────────────────────────────────────────────

(defn- now-ms [] (.getTime (js/Date.)))

(defn- iso->ms [s]
  (when s
    (let [d (js/Date. s)]
      (when-not (js/isNaN (.getTime d)) (.getTime d)))))

(defn- format-clock [ms]
  (.toLocaleTimeString (js/Date. ms) js/undefined
                       #js {:hour "2-digit" :minute "2-digit" :hour12 false}))

(defn- mins-remaining [end-ms]
  (max 0 (js/Math.round (/ (- end-ms (now-ms)) 60000))))

(defn- active-job? [{:keys [status]}]
  (contains? #{:running :pending :queued} (keyword status)))

;;; ── Now Playing ──────────────────────────────────────────────────────────────

(defn- content-event? [{:keys [kind]}] (#{nil "content"} kind))

(defn- live-event
  "The content event currently airing on a channel, tagged with :start-ms and
  :end-ms; nil when nothing is on air."
  [events]
  (let [now (now-ms)]
    (some (fn [ev]
            (when (content-event? ev)
              (let [s (iso->ms (or (:guide-start-at ev) (:start-at ev)))
                    e (iso->ms (or (:guide-finish-at ev) (:finish-at ev)))]
                (when (and s e (<= s now) (> e now))
                  (assoc ev :start-ms s :end-ms e)))))
          events)))

(defn- event-title [{:keys [custom-title media-item-id]} media-items]
  (let [item (get media-items media-item-id)]
    (or (not-empty custom-title)
        (when (map? item) (or (not-empty (:title item)) (not-empty (:name item))))
        (when media-item-id (str "Item #" media-item-id))
        "Untitled")))

(defn- now-playing-card [channel ev media-items]
  [card {:class "flex flex-col"}
   [card-header {:class "pb-3"}
    [card-title {:class "text-base"}
     [:button {:class    "hover:text-primary truncate text-left w-full"
               :on-click #(rf/dispatch [::events/navigate-to-channel (:id channel)])}
      (:name channel)]]]
   [card-content {:class "flex-1 pt-0"}
    (if ev
      (let [title (event-title ev media-items)
            span  (- (:end-ms ev) (:start-ms ev))
            prog  (when (pos? span)
                    (min 100 (max 0 (js/Math.round (* 100 (/ (- (now-ms) (:start-ms ev)) span))))))]
        [:div {:class "space-y-2"}
         (if-let [mid (:media-item-id ev)]
           [:button {:class    "text-sm font-medium hover:text-primary hover:underline underline-offset-4 text-left line-clamp-2"
                     :on-click #(rf/dispatch [::events/navigate-to-media-detail mid])}
            title]
           [:span {:class "text-sm font-medium line-clamp-2"} title])
         (when prog
           [:div {:class "h-1.5 w-full rounded bg-muted overflow-hidden"}
            [:div {:class "h-full rounded bg-primary" :style {:width (str prog "%")}}]])
         [:p {:class "text-xs text-muted-foreground tabular-nums"}
          (str (format-clock (:start-ms ev)) " – " (format-clock (:end-ms ev))
               " · " (mins-remaining (:end-ms ev)) "m left")]])
      [:p {:class "text-sm text-muted-foreground"} "Nothing on air right now."])]])

(defn- now-playing-section [channels channel-evs media-items]
  [:section {:class "space-y-3"}
   [:div {:class "flex items-center justify-between gap-4"}
    [:h2 {:class "text-lg font-semibold tracking-tight"} "Now Playing"]
    [button {:size :sm :variant :ghost
             :on-click #(rf/dispatch [::events/navigate :schedule-grid])}
     "Open Guide →"]]
   (cond
     (nil? channels)
     [:p {:class "text-sm text-muted-foreground"} "Loading channels…"]

     (empty? channels)
     [:p {:class "text-sm text-muted-foreground"}
      "No channels yet. Sync channels from the Guide."]

     :else
     [:div {:class "grid gap-4 sm:grid-cols-2 lg:grid-cols-3"}
      (for [ch channels]
        ^{:key (:id ch)}
        [now-playing-card ch (live-event (get channel-evs (:id ch) [])) media-items])])])

;;; ── Active jobs ──────────────────────────────────────────────────────────────

(defn- job-status-dot [status]
  [:span {:class (str "h-2 w-2 shrink-0 rounded-full "
                      (case (keyword status)
                        :running           "bg-blue-400 animate-pulse"
                        (:queued :pending) "bg-yellow-400"
                        "bg-muted-foreground"))}])

(defn- active-jobs-section [jobs]
  (let [active (filter active-job? jobs)]
    [:section {:class "space-y-3"}
     [:div {:class "flex items-center justify-between gap-4"}
      [:h2 {:class "text-lg font-semibold tracking-tight"} "Active Jobs"]
      [button {:size :sm :variant :ghost
               :on-click #(rf/dispatch [::events/navigate :jobs])}
       "All jobs →"]]
     [card {}
      [card-content {:class "p-0"}
       (cond
         (nil? jobs)
         [:p {:class "p-4 text-sm text-muted-foreground"} "Loading…"]

         (empty? active)
         [:p {:class "p-4 text-sm text-muted-foreground"} "Nothing running right now."]

         :else
         [:div {:class "divide-y divide-border"}
          (for [{:keys [id type status progress source]} active]
            ^{:key id}
            (let [{:keys [total completed failed skipped phase]} progress
                  done (+ (or completed 0) (or failed 0) (or skipped 0))]
              [:div {:class "flex items-center gap-3 px-4 py-3"}
               [job-status-dot status]
               [:span {:class "text-sm font-medium truncate"}
                (or (some-> type name) (str type))]
               (when source
                 [:span {:class "text-xs text-muted-foreground border border-border rounded px-1 shrink-0"}
                  (case source
                    :pseudovision     "Pseudovision"
                    :tunarr-scheduler "Tunarr Scheduler"
                    (name source))])
               [:div {:class "ml-auto flex items-center gap-2 shrink-0"}
                (when phase
                  [:span {:class "text-xs text-muted-foreground"} phase])
                (when (and total (pos? total))
                  [:span {:class "text-xs text-muted-foreground tabular-nums"}
                   (str done " / " total)])]]))])]]]))

;;; ── Quick links ──────────────────────────────────────────────────────────────

(def ^:private quick-links
  [{:page :schedule-grid :label "Guide"       :desc "Live TV-style grid across all channels"}
   {:page :media         :label "Media"       :desc "Browse the Pseudovision catalog"}
   {:page :browse        :label "Browse"      :desc "Explore the catalog by tag or dimension"}
   {:page :collections   :label "Collections" :desc "Curate custom media lists"}
   {:page :jobs          :label "Jobs"        :desc "Background processing & tag curation"}
   {:page :api-docs      :label "API Docs"    :desc "Service API references"}])

(defn- quick-links-section []
  [:section {:class "space-y-3"}
   [:h2 {:class "text-lg font-semibold tracking-tight"} "Explore"]
   [:div {:class "grid gap-3 sm:grid-cols-2 lg:grid-cols-3"}
    (for [{:keys [page label desc]} quick-links]
      ^{:key page}
      [card {:class    "cursor-pointer transition-colors hover:border-primary/50"
             :on-click #(rf/dispatch [::events/navigate page])}
       [card-header {:class "p-4"}
        [card-title {:class "text-base"} label]
        [card-description {} desc]]])]])

;;; ── Stats ────────────────────────────────────────────────────────────────────

(defn- stat [label value & [accent?]]
  [card {}
   [card-content {:class "p-4"}
    [:p {:class (str "text-2xl font-bold tabular-nums " (when accent? "text-primary"))} value]
    [:p {:class "text-xs uppercase tracking-wide text-muted-foreground"} label]]])

;;; ── Page ─────────────────────────────────────────────────────────────────────

(defn page []
  (let [channels    @(rf/subscribe [::subs/channels])
        channel-evs @(rf/subscribe [::subs/all-channel-events])
        media-items @(rf/subscribe [::subs/media-items-map])
        jobs        @(rf/subscribe [::subs/jobs])
        collections @(rf/subscribe [::subs/collections])
        active-jobs (count (filter active-job? jobs))
        on-air      (count (keep #(live-event (get channel-evs (:id %) [])) (or channels [])))]
    [:div {:class "space-y-8"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Marquee"]
      [:p {:class "text-muted-foreground"}
       "Control room for Pseudovision channels and the Tunarr scheduler."]]

     [:div {:class "grid gap-3 grid-cols-2 sm:grid-cols-4"}
      [stat "Channels"    (if channels (count channels) "—")]
      [stat "On Air Now"  on-air true]
      [stat "Collections" (count collections)]
      [stat "Active Jobs" active-jobs (pos? active-jobs)]]

     [now-playing-section channels channel-evs media-items]
     [active-jobs-section jobs]
     [quick-links-section]]))
