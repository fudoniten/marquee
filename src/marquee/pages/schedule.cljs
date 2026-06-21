(ns marquee.pages.schedule
  "Channel schedule views:
   - `grid-page`    — TV-guide-style grid: channels as rows, time as columns
   - `channel-page` — single-channel upcoming schedule as a vertical list"
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.copy-button :refer [copy-button]]
            [marquee.components.card :refer [card card-content]]))

;; ---------------------------------------------------------------------------
;; Time helpers
;; ---------------------------------------------------------------------------

(defn- now-ms [] (.getTime (js/Date.)))

(defn- iso->ms [s]
  (when s (.getTime (js/Date. s))))

(defn- ms->iso [ms]
  (.toISOString (js/Date. ms)))

(defn- format-time [ms]
  (.toLocaleTimeString (js/Date. ms) js/undefined
                       #js {:hour "2-digit" :minute "2-digit" :hour12 false}))

(defn- format-date-time [ms]
  (let [d (js/Date. ms)]
    (str (.toLocaleDateString d js/undefined
                              #js {:weekday "short" :month "short" :day "numeric"})
         " "
         (.toLocaleTimeString d js/undefined
                              #js {:hour "2-digit" :minute "2-digit" :hour12 false}))))

(defn- duration-str [start-ms end-ms]
  (let [mins (js/Math.round (/ (- end-ms start-ms) 60000))]
    (if (>= mins 60)
      (str (js/Math.floor (/ mins 60)) "h " (mod mins 60) "m")
      (str mins "m"))))

;; ---------------------------------------------------------------------------
;; Guide helpers — normalise a PlayoutEvent for display
;; ---------------------------------------------------------------------------

(defn- event-title
  "Best available label for a playout event: an explicit custom title, else the
  referenced media item's name (resolved from the cache), else a bare id."
  [{:keys [custom-title media-item-id]} media-items]
  (let [item (get media-items media-item-id)]
    (or (not-empty custom-title)
        (when (map? item) (or (not-empty (:title item)) (not-empty (:name item))))
        (when media-item-id (str "Item #" media-item-id))
        "Untitled")))

(defn- event->display
  "Convert a raw PlayoutEvent (ISO-8601 strings) to a display map with :ms times.
  `media-items` is the cached id→item map used to resolve a human-readable title."
  ([ev] (event->display ev {}))
  ([{:keys [start-at finish-at guide-start-at guide-finish-at] :as ev} media-items]
   (let [start-ms (iso->ms (or guide-start-at start-at))
         end-ms   (iso->ms (or guide-finish-at finish-at))]
     (when (and start-ms end-ms)
       (assoc ev
              :start-ms start-ms
              :end-ms   end-ms
              :title    (event-title ev media-items))))))

(defn- content-event? [{:keys [kind]}]
  (#{nil "content"} kind))

;; ---------------------------------------------------------------------------
;; Grid page  (multi-channel guide)
;; ---------------------------------------------------------------------------

(def ^:private grid-window-ms (* 2 60 60 1000)) ; 2-hour visible window
(def ^:private px-per-min 3)

(defn- time-ruler [window-start window-end]
  (let [total-min (/ (- window-end window-start) 60000)]
    [:div {:class "relative h-8 border-b bg-muted/30 flex-shrink-0"
           :style {:width (str (* total-min px-per-min) "px") :min-width "100%"}}
     (for [t (range 0 (inc total-min) 30)]
       ^{:key t}
       [:span {:class "absolute text-xs text-muted-foreground top-1"
               :style {:left (str (* t px-per-min) "px")}}
        (format-time (+ window-start (* t 60000)))])]))

(defn- grid-slot [{:keys [title start-ms end-ms kind]} window-start window-end on-click]
  (let [now           (now-ms)
        clamped-start (max start-ms window-start)
        clamped-end   (min end-ms   window-end)
        left-min      (/ (- clamped-start window-start) 60000)
        width-min     (/ (- clamped-end clamped-start) 60000)
        live?         (and (<= start-ms now) (> end-ms now))
        past?         (< end-ms now)]
    [:div {:class    (str "absolute top-1 bottom-1 rounded px-1 overflow-hidden cursor-pointer "
                          "border text-xs flex items-center select-none "
                          (cond
                            live? "bg-primary/20 border-primary font-medium"
                            past? "bg-muted/40 border-muted text-muted-foreground opacity-60"
                            :else "bg-card border-border hover:bg-accent"))
          :style     {:left  (str (* left-min px-per-min) "px")
                      :width (str (max 2 (* width-min px-per-min)) "px")}
          :title     (str title "\n" (format-time start-ms) " – " (format-time end-ms))
          :on-click  #(when on-click (on-click))}
     [:span {:class "truncate"} title]]))

(defn- channel-row [channel window-start window-end events media-items on-click-channel]
  (let [total-min   (/ (- window-end window-start) 60000)
        visible     (->> events
                         (filter content-event?)
                         (keep #(event->display % media-items))
                         (filter #(and (< (:start-ms %) window-end)
                                       (> (:end-ms %)  window-start))))]
    [:div {:class "flex border-b"}
     [:div {:class    "w-32 flex-shrink-0 flex items-center px-3 py-2 bg-muted/20 border-r text-sm font-medium truncate cursor-pointer hover:bg-accent"
            :title    (:name channel)
            :on-click on-click-channel}
      (:name channel)]
     [:div {:class "relative flex-1 overflow-hidden"
            :style {:height "44px" :min-width (str (* total-min px-per-min) "px")}}
      (for [ev visible]
        ^{:key (str (:start-ms ev) "-" (:title ev))}
        ;; Clicking a slot opens the media item it plays; fall back to the
        ;; channel page when there's no item to link to (e.g. filler/offline).
        [grid-slot ev window-start window-end
         (if-let [mid (:media-item-id ev)]
           #(rf/dispatch [::events/navigate-to-media-detail mid])
           #(rf/dispatch [::events/navigate-to-channel (:id channel)]))])]]))

(defn grid-page []
  (let [channels     @(rf/subscribe [::subs/channels])
        channel-evs  @(rf/subscribe [::subs/all-channel-events])
        media-items  @(rf/subscribe [::subs/media-items-map])
        loading?     @(rf/subscribe [::subs/channels-loading?])
        window-start @(rf/subscribe [::subs/schedule-window-start])
        window-end   (+ window-start grid-window-ms)]
    [:div {:class "space-y-4"}
     [:div {:class "flex items-center justify-between flex-wrap gap-4"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"} "Guide"]
       [:p {:class "text-muted-foreground"} "What's playing across all channels."]]
      [:div {:class "flex items-center gap-2 flex-wrap"}
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-back])}
        "← Back"]
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-reset])}
        "Now"]
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-forward])}
        "Forward →"]
       [:div {:class "w-px h-5 bg-border"}]
       [action-btn {:action-key :sync-channels
                    :label      "Sync Channels"
                    :on-click   #(rf/dispatch [::events/trigger-sync-channels])}]]]

     (cond
       loading?
       [:p {:class "text-muted-foreground"} "Loading channels…"]

       (empty? channels)
       [:p {:class "text-muted-foreground"} "No channels found."]

       :else
       [:div {:class "border rounded-lg overflow-auto"}
        [:div {:class "flex"}
         [:div {:class "w-32 flex-shrink-0 bg-muted/20 border-b border-r h-8"}]
         [:div {:class "flex-1 overflow-x-auto"}
          [time-ruler window-start window-end]]]
        (for [ch channels
              :let [evs (get channel-evs (:id ch) [])]]
          ^{:key (:id ch)}
          [channel-row ch window-start window-end evs media-items
           #(rf/dispatch [::events/navigate-to-channel (:id ch)])])])]))

;; ---------------------------------------------------------------------------
;; Channel (single) page
;; ---------------------------------------------------------------------------

(defn- channel-stream-url
  "Absolute HLS playback URL for a channel, served by Pseudovision at
  `/stream/{uuid}`. Built directly against the Pseudovision base URL (not the
  BFF proxy) so the link works when opened in an external HLS player. Keys off
  the channel's UUID — the numeric id/number is not a valid stream identifier."
  [pseudovision-url channel-uuid]
  (when (and pseudovision-url channel-uuid)
    (str pseudovision-url "/stream/" channel-uuid)))

(defn- channel-playback [stream-url]
  [:div {:class "flex items-center gap-2 flex-wrap"}
   [:span {:class "text-sm font-medium text-muted-foreground"} "Playback:"]
   [:a {:href   stream-url
        :target "_blank"
        :rel    "noopener noreferrer"
        :class  "inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"}
    "Watch ↗"]
   [:code {:class "text-xs text-muted-foreground bg-muted rounded px-1.5 py-0.5 break-all"}
    stream-url]
   [copy-button {:text stream-url :label "Copy URL"}]])

(defn- playout-generating-banner
  "Shown on the channel page while Pseudovision has an active playout
  generation job for this channel, so a rebuild started elsewhere (or one
  that simply takes a while) is visible without re-triggering it."
  [job]
  [:div {:class "flex items-center gap-2 rounded-md border border-blue-300 bg-blue-50 px-3 py-2 text-sm text-blue-800"}
   [:span {:class "h-3 w-3 rounded-full border-2 border-blue-400 border-t-transparent animate-spin"}]
   [:span "Generating playout…"]
   (let [{:keys [phase total completed failed skipped]} (:progress job)
         done (+ (or completed 0) (or failed 0) (or skipped 0))]
     (when (and total (pos? total))
       [:span {:class "text-blue-600"} (str done " / " total (when phase (str " · " phase)))]))])

(defn- schedule-entry [{:keys [start-ms end-ms title kind media-item-id] :as ev}]
  (let [now       (now-ms)
        live?     (and (<= start-ms now) (> end-ms now))
        past?     (< end-ms now)
        linkable? (and media-item-id (content-event? ev))]
    [:div {:class (str "flex gap-4 py-4 border-b last:border-0 "
                       (when past? "opacity-50"))}
     [:div {:class "flex-1 min-w-0"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       (when live?
         [:span {:class "text-xs font-bold text-primary uppercase tracking-wide"} "LIVE"])
       (if linkable?
         [:a {:class    "text-sm font-medium cursor-pointer underline-offset-4 hover:underline hover:text-primary"
              :on-click #(rf/dispatch [::events/navigate-to-media-detail media-item-id])}
          title]
         [:span {:class "text-sm font-medium"} title])
       (when (and kind (not= kind "content"))
         [:span {:class "text-xs text-muted-foreground border rounded px-1"} kind])]
      [:p {:class "text-xs text-muted-foreground mt-0.5"}
       (str (format-date-time start-ms) " · " (duration-str start-ms end-ms))]]]))

(defn channel-page []
  (let [channel     @(rf/subscribe [::subs/current-channel])
        raw-events  @(rf/subscribe [::subs/current-channel-events])
        media-items @(rf/subscribe [::subs/media-items-map])
        loading?    @(rf/subscribe [::subs/channel-events-loading?])
        channels    @(rf/subscribe [::subs/channels])
        pv-url      @(rf/subscribe [::subs/pseudovision-url])
        playout-job @(rf/subscribe [::subs/channel-playout-job (:id channel)])
        stream-url  (channel-stream-url pv-url (:uuid channel))
        entries     (->> (or raw-events [])
                         (keep #(event->display % media-items))
                         (sort-by :start-ms))]
    [:div {:class "space-y-6"}
     [:div {:class "flex items-start justify-between gap-4 flex-wrap"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"}
        (or (:name channel) "Schedule")]
       (when (:description channel)
         [:p {:class "text-muted-foreground"} (:description channel)])]
      [:div {:class "flex items-center gap-2 flex-wrap"}
       (when (:id channel)
         [action-btn {:action-key [:rebuild-playout (:id channel)]
                      :label      (if playout-job "Generating…" "Rebuild Playout")
                      :disabled   (boolean playout-job)
                      :on-click   #(rf/dispatch [::events/trigger-rebuild-playout (:id channel)])}])
       (when (seq channels)
         [:select {:class     "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm"
                   :value     (or (:id channel) "")
                   :on-change #(rf/dispatch [::events/navigate-to-channel
                                             (js/parseInt (.. % -target -value))])}
          [:option {:value "" :disabled true} "Select channel…"]
          (for [ch channels]
            ^{:key (:id ch)}
            [:option {:value (:id ch)} (:name ch)])])]]

     (when stream-url
       [channel-playback stream-url])

     (when playout-job
       [playout-generating-banner playout-job])

     (cond
       loading?
       [:p {:class "text-muted-foreground"} "Loading schedule…"]

       (nil? channel)
       [:p {:class "text-muted-foreground"} "Select a channel above."]

       (empty? entries)
       [:p {:class "text-muted-foreground"} "No upcoming events."]

       :else
       [card {}
        [card-content {:class "p-0 divide-y"}
         (for [entry entries]
           ^{:key (str (:start-ms entry) "-" (:media-item-id entry))}
           [schedule-entry entry])]])]))
