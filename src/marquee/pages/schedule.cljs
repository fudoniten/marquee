(ns marquee.pages.schedule
  "Channel schedule views:
   - `grid-page`    — TV-guide-style grid: channels as rows, time as columns
   - `channel-page` — single-channel upcoming schedule as a vertical list"
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.copy-button :refer [copy-button]]
            [marquee.components.card :refer [card card-content]]
            [marquee.lib.periods :as periods]
            [marquee.lib.utils :as utils]))

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
  referenced media item's ancestor-qualified name (e.g. \"Episode 13 - Season 2
  - The Show\", resolved from the cache), else a bare id."
  [{:keys [custom-title media-item-id] :as ev} media-items]
  (let [item (get media-items media-item-id)]
    (or (not-empty custom-title)
        (utils/event-guide-title ev)
        (when (map? item) (or (not-empty (utils/media-display-name item media-items))
                              (not-empty (:title item))))
        (not-empty (:title ev))
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

(defn- content-event?
  "Whether a PlayoutEvent represents an actual playing slot worth showing in
  the guide/grid, as opposed to a true gap (e.g. \"offline\" dead air with
  nothing scheduled). Historically this only matched :kind nil/\"content\", but
  Grout-sourced filler/bumper slots come through with roles like \"pre\",
  \"mid\", \"post\", \"pad\", \"tail\", \"fallback\", or \"bumper\" — those
  reference real media items too and were being dropped from the grid
  entirely. Any event backed by a media-item-id is real, playable content
  regardless of its role."
  [{:keys [kind media-item-id]}]
  (boolean (or media-item-id (#{nil "content"} kind))))

;; ---------------------------------------------------------------------------
;; Grid page  (multi-channel guide)
;;
;; The timeline is laid out with percentage positions so the guide always fills
;; the available width (rather than a fixed pixels-per-minute canvas). Slots are
;; colour-coded by the library their media item belongs to, so the mix of
;; content on each channel reads at a glance.
;; ---------------------------------------------------------------------------

(def ^:private grid-window-ms (* 3 60 60 1000)) ; 3-hour visible window
(def ^:private tick-ms (* 30 60 1000))          ; ruler tick / gridline every 30 min
(def ^:private label-col "w-40")                ; channel-name column width

(defn- pct [x] (str x "%"))

;; Faint 30-minute vertical gridlines, shared by the ruler and every row so
;; columns line up. The 16.6667% step = tick-ms / grid-window-ms.
(def ^:private gridline-style
  {:background-image
   "repeating-linear-gradient(to right, hsl(var(--border) / 0.6) 0 1px, transparent 1px 16.6667%)"})

;; ── Library colour-coding ──────────────────────────────────────────────────
;; A fixed hue wheel; a library's key hashes to one of these so its colour is
;; stable across renders. Colours are applied as inline styles (HSL), so they
;; don't depend on Tailwind scanning dynamically-built class names.
(def ^:private library-hues [45 331 199 152 265 24 96 288 220 12])

(defn- str-hash [s]
  (reduce (fn [acc ch] (bit-and (+ (* acc 31) (.charCodeAt ch 0)) 0x7fffffff))
          7 (seq (str s))))

(defn- hue-for [k]
  (nth library-hues (mod (str-hash k) (count library-hues))))

(defn- item->library
  "Best-effort {:key :label} for the library a media item belongs to. Falls back
  to the item's kind when no library field is present, so the guide is still
  colour-differentiated even if the catalog doesn't expose libraries."
  [item libraries-by-id]
  (when (map? item)
    (let [lid   (or (:library-id item) (:libraryId item))
          lname (or (:library-name item) (:libraryName item)
                    (when (string? (:library item)) (:library item))
                    (get libraries-by-id lid))]
      (cond
        (some? lid)   {:key (str "lib:" lid)   :label (or lname (str "Library " lid))}
        (some? lname) {:key (str "lib:" lname) :label lname}
        (:kind item)  {:key (str "kind:" (name (:kind item))) :label (name (:kind item))}
        :else         nil))))

(defn- library-color-style [hue past?]
  {:border-color      (str "hsl(" hue " 70% 55%)")
   :background         (str "hsla(" hue ", 70%, 50%, " (if past? "0.08" "0.20") ")")
   :border-left-width "3px"})

(defn- now-line [window-start window-end]
  (let [now  (now-ms)
        span (- window-end window-start)]
    (when (and (>= now window-start) (< now window-end))
      [:div {:class "pointer-events-none absolute top-0 bottom-0 w-0.5 bg-accent z-20"
             :style {:left (pct (* 100 (/ (- now window-start) span)))}}])))

(defn- time-ruler [window-start window-end]
  (let [span    (- window-end window-start)
        n-ticks (js/Math.round (/ span tick-ms))]
    [:div {:class "relative h-8 border-b border-border bg-muted/30"
           :style gridline-style}
     ;; Skip the final tick (it sits at 100% and would clip past the edge).
     (for [t (range 0 n-ticks)
           :let [left (* 100 (/ (* t tick-ms) span))]]
       ^{:key t}
       [:span {:class "absolute top-2 pl-1 text-[11px] tabular-nums text-muted-foreground"
               :style {:left (pct left)}}
        (format-time (+ window-start (* t tick-ms)))])
     [now-line window-start window-end]]))

(def ^:private short-pct
  "Slots narrower than this share of the window (4% of 3h ≈ 7 min) are too
  small to label: runs of them are drawn as one \"short items\" block instead
  of a row of overlapping slivers."
  4)

(defn- span-pct [start-ms end-ms window-start window-end]
  (let [span (- window-end window-start)
        cs   (max start-ms window-start)
        ce   (min end-ms window-end)]
    (* 100 (/ (max 0 (- ce cs)) span))))

(defn- slot-geometry [start-ms end-ms window-start window-end]
  (let [span (- window-end window-start)
        cs   (max start-ms window-start)]
    {:left  (* 100 (/ (- cs window-start) span))
     :width (span-pct start-ms end-ms window-start window-end)}))

(defn- grid-slot [{:keys [title start-ms end-ms]} hue window-start window-end on-click]
  (let [now   (now-ms)
        {:keys [left width]} (slot-geometry start-ms end-ms window-start window-end)
        live? (and (<= start-ms now) (> end-ms now))
        past? (< end-ms now)
        label? (>= width short-pct)]
    [:div {:class    (str "absolute top-1 bottom-1 rounded-sm overflow-hidden cursor-pointer "
                          "border flex flex-col justify-center leading-tight select-none "
                          "transition-[filter] hover:brightness-125 "
                          (if label? "px-1.5 " "px-0 ")
                          (cond
                            live? "ring-1 ring-primary font-semibold "
                            past? "opacity-60 "
                            :else "")
                          ;; Fallback styling when we can't determine a library.
                          (when-not hue
                            (cond
                              live? "bg-primary/20 border-primary"
                              past? "bg-muted/40 border-muted text-muted-foreground"
                              :else "bg-card border-border")))
          :style    (merge {:left (pct left) :width (pct width) :min-width "2px"}
                           (when hue (library-color-style hue past?)))
          :title    (str title "\n" (format-time start-ms) " – " (format-time end-ms))
          :on-click #(when on-click (on-click))}
     (when label?
       (list
        (when live?
          ^{:key "live"} [:span {:class "text-[9px] font-bold uppercase tracking-wide text-primary"} "Live"])
        ^{:key "title"} [:span {:class "truncate text-xs"} title]))]))

(defn- cluster-slot
  "One block standing in for a run of adjacent short slots (filler, bumpers,
  promos). The tooltip lists what's in it; clicking opens the channel page."
  [{:keys [start-ms end-ms events]} window-start window-end on-click]
  (let [now   (now-ms)
        {:keys [left width]} (slot-geometry start-ms end-ms window-start window-end)
        live? (and (<= start-ms now) (> end-ms now))
        past? (< end-ms now)
        n     (count events)
        shown 12]
    [:div {:class    (str "absolute top-1 bottom-1 rounded-sm overflow-hidden cursor-pointer border "
                          "border-dashed border-border bg-muted/50 text-muted-foreground "
                          "flex items-center justify-center select-none hover:brightness-110 "
                          (cond live? "ring-1 ring-primary " past? "opacity-60 " :else ""))
          :style    {:left (pct left) :width (pct width) :min-width "2px"
                     :background-image "repeating-linear-gradient(135deg, transparent 0 4px, hsl(var(--border) / 0.5) 4px 5px)"}
          :title    (str n " short items, " (format-time start-ms) " – " (format-time end-ms) "\n"
                         (str/join "\n" (map (fn [{:keys [title start-ms]}]
                                               (str (format-time start-ms) "  " title))
                                             (take shown events)))
                         (when (> n shown) (str "\n… and " (- n shown) " more")))
          :on-click #(when on-click (on-click))}
     (when (>= width short-pct)
       [:span {:class "truncate text-[11px] px-1"} (str n " short items")])]))

(defn- layout-row
  "Groups a row's visible events into render segments: a long event stays a
  {:event ev}; a run of adjacent short ones (each < `short-pct` of the window,
  separated by under a minute) becomes {:cluster {...}} when there are two or
  more, else stays a single unlabeled sliver."
  [events window-start window-end]
  (let [short? #(< (span-pct (:start-ms %) (:end-ms %) window-start window-end) short-pct)
        flush  (fn [segs run]
                 (cond
                   (empty? run)      segs
                   (= 1 (count run)) (conj segs {:event (first run)})
                   :else             (conj segs {:cluster {:start-ms (:start-ms (first run))
                                                           :end-ms   (:end-ms (peek run))
                                                           :events   run}})))]
    (loop [[ev & more] events, run [], segs []]
      (cond
        (nil? ev)
        (flush segs run)

        (short? ev)
        (if (and (seq run) (> (- (:start-ms ev) (:end-ms (peek run))) 60000))
          (recur more [ev] (flush segs run))
          (recur more (conj run ev) segs))

        :else
        (recur more [] (conj (flush segs run) {:event ev}))))))

(defn- row-spinner []
  [:span {:class "inline-block h-3 w-3 shrink-0 rounded-full border-2 border-muted-foreground/40 border-t-transparent animate-spin"
          :aria-label "Loading"}])

(defn- channel-row [channel window-start window-end events loading? media-items libraries-by-id on-click-channel]
  (let [visible (->> events
                     (filter content-event?)
                     (keep #(event->display % media-items))
                     (filter #(and (< (:start-ms %) window-end)
                                   (> (:end-ms %)  window-start)))
                     (sort-by :start-ms))
        to-channel #(rf/dispatch [::events/navigate-to-channel (:id channel)])]
    [:div {:class "flex border-b border-border last:border-0"}
     [:div {:class    (str label-col " flex-shrink-0 flex items-center gap-2 px-3 py-2 bg-muted/30 "
                           "border-r border-border text-sm font-medium cursor-pointer "
                           "hover:bg-accent hover:text-accent-foreground")
            :title    (:name channel)
            :on-click on-click-channel}
      [:span {:class "truncate"} (:name channel)]
      (when loading? [row-spinner])]
     [:div {:class "relative flex-1" :style (merge {:height "56px"} gridline-style)}
      [now-line window-start window-end]
      (when (and loading? (empty? visible))
        [:div {:class "absolute inset-1 rounded-sm bg-muted/60 animate-pulse"}])
      (for [{:keys [event cluster]} (layout-row visible window-start window-end)]
        (if cluster
          ^{:key (str "c-" (:start-ms cluster))}
          [cluster-slot cluster window-start window-end to-channel]
          (let [ev   event
                item (get media-items (:media-item-id ev))
                lib  (or (utils/event-library ev) (item->library item libraries-by-id))
                hue  (when lib (hue-for (:key lib)))]
            ^{:key (str (:start-ms ev) "-" (:title ev))}
            ;; Clicking a slot opens the media item it plays; fall back to the
            ;; channel page when there's no item to link to (e.g. offline).
            [grid-slot ev hue window-start window-end
             (if-let [mid (:media-item-id ev)]
               #(rf/dispatch [::events/navigate-to-media-detail mid])
               to-channel)])))]]))

(defn- used-libraries
  "Distinct {:key :label} libraries appearing among the guide's content events,
  for the legend."
  [channel-evs media-items libraries-by-id]
  (->> (mapcat val channel-evs)
       (filter content-event?)
       (keep #(or (utils/event-library %)
                  (some-> (:media-item-id %) (->> (get media-items))
                          (item->library libraries-by-id))))
       (distinct)
       (sort-by :label)))

(defn- library-legend [libs]
  [:div {:class "flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground"}
   [:span {:class "font-medium text-foreground"} "Libraries:"]
   (for [{:keys [key label]} libs]
     ^{:key key}
     [:span {:class "inline-flex items-center gap-1.5"}
      [:span {:class "inline-block h-2.5 w-2.5 rounded-sm"
              :style {:background (str "hsl(" (hue-for key) " 70% 55%)")}}]
      label])])

(defn grid-page []
  (let [channels        @(rf/subscribe [::subs/channels])
        channel-evs     @(rf/subscribe [::subs/all-channel-events])
        media-items     @(rf/subscribe [::subs/media-items-map])
        libraries       @(rf/subscribe [::subs/media-libraries])
        loading?        @(rf/subscribe [::subs/channels-loading?])
        evs-loading     @(rf/subscribe [::subs/channel-events-loading-set])
        window-start    @(rf/subscribe [::subs/schedule-window-start])
        window-end      (+ window-start grid-window-ms)
        libraries-by-id (into {} (map (juxt :id :name) (or libraries [])))
        legend          (used-libraries channel-evs media-items libraries-by-id)]
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
       [:div {:class "space-y-3"}
        (when (seq legend)
          [library-legend legend])
        [:div {:class "border border-border rounded-lg overflow-hidden"}
         ;; Header: time ruler, offset by the channel-label column.
         [:div {:class "flex"}
          [:div {:class (str label-col " flex-shrink-0 bg-muted/30 border-b border-r border-border h-8")}]
          [:div {:class "flex-1"}
           [time-ruler window-start window-end]]]
         ;; One row per channel.
         (for [ch channels
               :let [evs (get channel-evs (:id ch) [])]]
           ^{:key (:id ch)}
           [channel-row ch window-start window-end evs (contains? evs-loading (:id ch))
            media-items libraries-by-id
            #(rf/dispatch [::events/navigate-to-channel (:id ch)])])]])]))

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

;; ── FFmpeg profile selector ──────────────────────────────────────────────────
;; These helpers isolate the profile/channel data shape (see the events ns for
;; the API contract).

(defn- profile-value
  "The id used to identify a profile in the <select> and in the PATCH body."
  [p]
  (:id p))

(defn- profile-label [p]
  (or (:name p) (str (profile-value p))))

(defn- channel-ffmpeg-profile-id
  "A channel's currently-assigned ffmpeg profile id."
  [channel]
  (:ffmpeg-profile-id channel))

(defn- ffmpeg-profile-selector
  "Dropdown to switch a channel's transcoding profile. Renders only when the
   profiles endpoint returned some profiles, so it stays hidden if the backend
   doesn't expose it."
  [channel]
  (let [profiles @(rf/subscribe [::subs/ffmpeg-profiles])]
    (when (and (:id channel) (sequential? profiles) (seq profiles))
      (let [state   @(rf/subscribe [::subs/action-state [:set-ffmpeg-profile (:id channel)]])
            current (channel-ffmpeg-profile-id channel)
            saving? (= :loading (:status state))]
        [:div {:class "flex items-center gap-2"}
         [:label {:class "text-sm text-muted-foreground"} "Profile:"]
         [:select {:class     "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm"
                   :value     (str (or current ""))
                   :disabled  saving?
                   :on-change (fn [e]
                                (let [v (.. e -target -value)
                                      ;; Send the profile's original-typed id, not
                                      ;; the stringified <option> value.
                                      chosen (some #(when (= (str (profile-value %)) v) %) profiles)]
                                  (when chosen
                                    (rf/dispatch [::events/set-channel-ffmpeg-profile
                                                  (:id channel) (profile-value chosen)]))))}
          (when (nil? current)
            [:option {:value "" :disabled true} "Select profile…"])
          (for [p profiles]
            ^{:key (profile-value p)}
            [:option {:value (str (profile-value p))} (profile-label p)])]
         (case (:status state)
           :loading [:span {:class "text-xs text-muted-foreground"} "Saving…"]
           :success [:span {:class "text-xs text-primary"} "Saved ✓"]
           :error   [:span {:class "text-xs text-destructive"} (or (:message state) "Error")]
           nil)]))))

;; ── Scheduling guidance ───────────────────────────────────────────────────────

(def ^:private guidance-textarea-class
  "flex w-full min-h-[5rem] rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")

(def ^:private text-input-class
  "flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")

(def ^:private select-class
  "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm")

(defn- section-label [text]
  [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"} text])

(defn- channel-guidance-slug
  "Identifier Tunarr Scheduler keys a channel under: the channel's name in the
   lower-cased slug form its scheduling endpoints use (e.g. \"Spectrum\" →
   \"spectrum\"). Shared by guidance, the quarterly/monthly/daily scheduling
   triggers, the quarterly outline and the monthly overrides — all keyed by the
   same identifier. Adjust here if the scheduler ever keys any of these by a
   different field."
  [channel]
  (some-> (:name channel) str/trim str/lower-case not-empty))

(def ^:private guidance-field-meta
  ;; [key label hint multiline?]
  [[:strategic_guidance "Strategic guidance"
    "Standing rules for this channel, fed to every quarterly and monthly run. e.g. Air Seinfeld at least twice a week in primetime. Adult content after 22:00 only."
    true]
   [:quarterly_theme "Quarterly theme"
    "Optional theme for the next quarterly grid, e.g. \"Autumn of mysteries\"."
    false]
   [:monthly_theme "Monthly theme"
    "Optional theme for the next monthly overrides, e.g. \"Halloween horror\"."
    false]
   [:planned_events "Planned events"
    "One per line, e.g. \"Dec 24: Christmas movie marathon\". Monthly runs turn these into overrides."
    true]])

(defn- guidance->draft [g]
  (let [g (if (map? g) g {})]
    {:strategic_guidance (or (:strategic_guidance g) "")
     :quarterly_theme    (or (:quarterly_theme g) "")
     :monthly_theme      (or (:monthly_theme g) "")
     :planned_events     (str/join "\n" (:planned_events g))}))

(defn- draft->guidance [d]
  {:strategic_guidance (str/trim (:strategic_guidance d))
   :quarterly_theme    (str/trim (:quarterly_theme d))
   :monthly_theme      (str/trim (:monthly_theme d))
   :planned_events     (->> (str/split-lines (:planned_events d))
                            (map str/trim)
                            (remove str/blank?)
                            vec)})

(defn- guidance-empty? [g]
  (not (and (map? g)
            (or (seq (:strategic_guidance g)) (seq (:quarterly_theme g))
                (seq (:monthly_theme g)) (seq (:planned_events g))))))

(defn- channel-guidance-card
  "View and edit a channel's scheduling guidance — the operator input Tunarr
   Scheduler feeds to Tunabrain: standing strategic guidance, quarterly and
   monthly themes, and planned events. Loads lazily on first render for the
   channel; the editor is always offered even when nothing is stored yet or
   the load failed."
  [_]
  (let [editing? (r/atom false)
        draft    (r/atom (guidance->draft nil))]
    (fn [channel]
      (let [slug    (channel-guidance-slug channel)
            wrapper @(rf/subscribe [::subs/channel-guidance slug])
            state   @(rf/subscribe [::subs/action-state [:channel-guidance slug]])
            saving? (= :loading (:status state))]
        ;; Trigger the load once (guarded on nil so this doesn't re-dispatch).
        (when (and slug (nil? wrapper))
          (rf/dispatch [::events/load-channel-guidance slug]))
        (let [start-edit (fn [] (reset! draft (guidance->draft wrapper)) (reset! editing? true))
              save       (fn []
                           (rf/dispatch [::events/set-channel-guidance slug (draft->guidance @draft)])
                           (reset! editing? false))]
          [card {}
           [card-content {:class "pt-6 space-y-3"}
            [:div {:class "flex items-center justify-between gap-2"}
             [:div {:class "flex items-center gap-2"}
              [section-label "Scheduling guidance"]
              (case (:status state)
                :loading [:span {:class "text-xs text-muted-foreground"} "Saving…"]
                :success [:span {:class "text-xs text-primary"} "Saved ✓"]
                :error   [:span {:class "text-xs text-destructive"} (or (:message state) "Error")]
                nil)]
             (when (and slug (not @editing?))
               [button {:size :sm :variant :outline :on-click start-edit}
                (if (guidance-empty? wrapper) "Add" "Edit")])]
            (cond
              (nil? slug)
              [:p {:class "text-sm text-muted-foreground"}
               "This channel has no name to key guidance by."]

              @editing?
              [:div {:class "space-y-3"}
               (for [[k label hint multiline?] guidance-field-meta]
                 ^{:key k}
                 [:label {:class "block space-y-1"}
                  [:span {:class "text-xs font-medium"} label]
                  (if multiline?
                    [:textarea {:class       guidance-textarea-class
                                :rows        (if (= k :strategic_guidance) 4 3)
                                :placeholder hint
                                :value       (get @draft k)
                                :disabled    saving?
                                :on-change   #(swap! draft assoc k (.. % -target -value))}]
                    [:input {:class       text-input-class
                             :type        "text"
                             :placeholder hint
                             :value       (get @draft k)
                             :disabled    saving?
                             :on-change   #(swap! draft assoc k (.. % -target -value))}])])
               [:div {:class "flex gap-2"}
                [button {:size :sm :disabled saving? :on-click save} "Save"]
                [button {:size :sm :variant :ghost :on-click #(reset! editing? false)} "Cancel"]]]

              (or (nil? wrapper) (= :loading wrapper))
              [:p {:class "text-sm text-muted-foreground"} "Loading guidance…"]

              (guidance-empty? wrapper)
              [:p {:class "text-sm text-muted-foreground"}
               "No guidance set. Add guidance, themes or planned events to steer how this channel is scheduled."]

              :else
              [:div {:class "space-y-2"}
               (for [[k label _ _] guidance-field-meta
                     :let [v (get wrapper k)]
                     :when (seq v)]
                 ^{:key k}
                 [:div
                  [:div {:class "text-xs font-medium text-muted-foreground"} label]
                  (if (sequential? v)
                    [:ul {:class "text-sm list-disc pl-5"}
                     (for [[i e] (map-indexed vector v)] ^{:key i} [:li e])]
                    [:p {:class "text-sm whitespace-pre-wrap break-words leading-relaxed"} v])])])]])))))

;; ── Scheduling pipeline ─────────────────────────────────────────────────────
;; On-demand runs of each stage Tunarr Scheduler's crons normally drive:
;; quarterly grid → monthly overrides → daily horizon extension, plus PV's hard
;; reset. See the "Channel scheduling pipeline" notes in marquee.events.

(defn- period-select
  "A <select> over `options` (each {:key .. :label ..}); `selected` is the
  selected option's :key and `on-select` gets the chosen option."
  [options selected on-select]
  [:select {:class     select-class
            :value     selected
            :on-change (fn [e]
                         (let [v (.. e -target -value)]
                           (on-select (some #(when (= v (:key %)) %) options))))}
   (for [{k :key label :label} options]
     ^{:key k} [:option {:value k} label])])

(defn- quarter-options []
  (let [cur (periods/current-quarter)
        nxt (periods/next-quarter)]
    [(assoc cur :key (periods/quarter-key cur) :label (str (:label cur) " (current)"))
     (assoc nxt :key (periods/quarter-key nxt) :label (str (:label nxt) " (next)"))]))

(defn- month-options []
  (let [cur (periods/current-month)
        nxt (periods/next-month)]
    [(assoc cur :key (:month cur) :label (str (:label cur) " (this month)"))
     (assoc nxt :key (:month nxt) :label (str (:label nxt) " (next month)"))]))

(defn- pipeline-row [title help & controls]
  [:div {:class "flex items-start justify-between gap-4 flex-wrap py-3 border-t first:border-0 first:pt-0"}
   [:div {:class "min-w-0 flex-1 basis-64"}
    [:div {:class "text-sm font-medium"} title]
    [:p {:class "text-xs text-muted-foreground"} help]]
   (into [:div {:class "flex items-start gap-2 flex-wrap"}] controls)])

(defn- reset-playout-button
  "PV's hard rebuild (from now): wipes the future timeline and restarts
  rotation. Two-step so it isn't confused with Extend Schedule."
  [_]
  (let [armed? (r/atom false)]
    (fn [{:keys [channel-id playout-job]}]
      (if @armed?
        [:div {:class "flex items-center gap-1"}
         [button {:size :sm :variant :destructive
                  :on-click (fn []
                              (reset! armed? false)
                              (rf/dispatch [::events/trigger-rebuild-playout channel-id]))}
          "Confirm reset"]
         [button {:size :sm :variant :ghost :on-click #(reset! armed? false)} "Cancel"]]
        [action-btn {:action-key [:rebuild-playout channel-id]
                     :label      (if playout-job "Generating…" "Reset Playout")
                     :disabled   (boolean playout-job)
                     :on-click   #(reset! armed? true)}]))))

(defn- channel-pipeline-card [_]
  (let [quarter (r/atom (first (quarter-options)))
        ;; Next month is the useful default: overrides only land past the
        ;; already-built 14-day horizon, and it's what the monthly cron runs.
        month   (r/atom (second (month-options)))]
    (fn [channel playout-job]
      (let [slug (channel-guidance-slug channel)
            next-q? (= (:key @quarter) (:key (second (quarter-options))))]
        [card {}
         [card-content {:class "pt-6 space-y-2"}
          [section-label "Schedule pipeline"]
          (if (nil? slug)
            [:p {:class "text-sm text-muted-foreground"}
             "This channel has no name to key its schedule by."]
            [:div
             [pipeline-row "1. Quarterly grid"
              (if next-q?
                "Freeze only: the grid is stored for review and applied by the 1st-of-quarter run. Freeze it before the 10th so next month's overrides can be built on it."
                "Propose, check and freeze this quarter's grid, then apply it to the playout from the end of the already-built timeline.")
              [period-select (quarter-options) (:key @quarter) #(reset! quarter %)]
              [action-btn {:action-key [:regenerate-quarterly slug]
                           :label      "Generate"
                           :on-click   #(rf/dispatch [::events/trigger-regenerate-quarterly slug @quarter])}]]
             [pipeline-row "2. Monthly overrides"
              "Propose date-specific overrides on top of the month's quarterly grid and re-sync the playout. Overrides only take effect beyond the 14 days already built."
              [period-select (month-options) (:key @month) #(reset! month %)]
              [action-btn {:action-key [:regenerate-monthly slug]
                           :label      "Generate"
                           :on-click   #(rf/dispatch [::events/trigger-regenerate-monthly slug @month])}]]
             [pipeline-row "3. Daily timeline"
              "Extend fills the timeline out to 14 days, keeping what's already scheduled. Reset throws away the future timeline and rebuilds it from now, restarting rotation."
              [action-btn {:action-key [:extend-schedule slug]
                           :label      "Extend Schedule"
                           :on-click   #(rf/dispatch [::events/trigger-extend-schedule slug (:id channel)])}]
              (when (:id channel)
                [reset-playout-button {:channel-id (:id channel) :playout-job playout-job}])]])]]))))

;; ── Quarterly outline ─────────────────────────────────────────────────────
;; Read-only view of the frozen daypart skeleton + rotation strips Tunarr
;; Scheduler generated for a channel's current quarter, plus the feasibility
;; snapshot it was frozen against. This is a summary of the *structure* the
;; quarter was built from, not the fully-expanded per-minute schedule (see
;; the event list below for that).

(defn- feasibility-badge [status]
  (let [[label cls] (case status
                       "ok"       ["OK"       "bg-green-100 text-green-800 border-green-300"]
                       "warnings" ["Warnings" "bg-yellow-100 text-yellow-800 border-yellow-300"]
                       "blocked"  ["Blocked"  "bg-red-100 text-red-800 border-red-300"]
                       [(str status) "bg-muted text-muted-foreground border-border"])]
    [:span {:class (str "text-xs font-medium px-2 py-0.5 rounded border shrink-0 " cls)} label]))

(defn- outline-days-label [days]
  (cond
    (string? days)     (str/capitalize days)
    (sequential? days) (str/join "/" (map str/upper-case days))
    :else              (str days)))

(defn- outline-daypart-row [{:keys [name start end role genre_focus]}]
  [:div {:class "flex items-baseline gap-3 flex-wrap py-1 text-sm border-t first:border-0"}
   [:span {:class "font-mono text-xs text-muted-foreground w-28 shrink-0"} (str start "–" end)]
   [:span {:class "font-medium"} name]
   (when (seq role) [:span {:class "text-xs text-muted-foreground"} role])
   (when (seq genre_focus)
     [:span {:class "text-xs text-muted-foreground"} (str/join ", " genre_focus)])])

;; Strips share a clock window across different day patterns (a Weekdays and a
;; Weekends strip can both start at 07:00 without ever colliding on a real day),
;; so a flat time sort interleaves them confusingly. Group by day pattern first,
;; then sort each group along its own broadcast day.

(def ^:private weekday-order
  {"mon" 0 "tue" 1 "wed" 2 "thu" 3 "fri" 4 "sat" 5 "sun" 6})

(defn- clock->minutes
  "Parse an \"HH:MM\" clock string to minutes past midnight; nil if unparseable."
  [s]
  (when-let [[_ h m] (and (string? s) (re-matches #"(\d{1,2}):(\d{2})" s))]
    (+ (* 60 (js/parseInt h 10)) (js/parseInt m 10))))

(defn- broadcast-minutes
  "Minutes from the broadcast-day start, so a group sorts the way its day
  actually airs — a 06:00 broadcast day runs 06:00 → 06:00, putting the
  after-midnight strips last rather than first."
  [start broadcast-start]
  (let [s (or (clock->minutes start) 0)
        b (or (clock->minutes broadcast-start) 0)]
    (mod (- s b) (* 24 60))))

(defn- days-group-key
  "A hashable grouping key for a strip's :days (an explicit list and any
  equivalent are collapsed to the same key)."
  [days]
  (if (sequential? days) (vec days) days))

(defn- days-sort-key
  "Ordering for day-pattern groups: the named patterns first in broadcast order
  (daily, weekdays, weekends), then any other named pattern, then explicit day
  lists by their earliest weekday."
  [days]
  (cond
    (= days "daily")    [0 0]
    (= days "weekdays") [1 0]
    (= days "weekends") [2 0]
    (string? days)      [3 0]
    (sequential? days)  [4 (apply min (map #(get weekday-order (str/lower-case (str %)) 99) days))]
    :else               [5 0]))

(defn- content-nav
  "The re-frame navigation event for a strip's media_id, or nil when it isn't a
  linkable reference. `series:`/`movie:` open the referenced item's media-detail
  page; `random:<category>` opens the tag browse for that category. A bare id or
  unknown prefix stays plain text."
  [media_id]
  (when (string? media_id)
    (let [[kind ident] (str/split media_id #":" 2)]
      (when (seq ident)
        (case kind
          ("series" "movie") [::events/navigate-to-media-detail ident]
          "random"           [::events/browse-select-item :tags ident]
          nil)))))

(defn- outline-content
  "The strip's content name — its label (e.g. a resolved show title) falling
  back to the raw media_id — rendered as a link to the referenced item/tag when
  one can be resolved."
  [{:keys [media_id label]}]
  (let [text (or label media_id)]
    (if-let [nav (content-nav media_id)]
      [:a {:class    "font-medium cursor-pointer underline-offset-4 hover:underline hover:text-primary"
           :on-click #(rf/dispatch nav)}
       text]
      [:span {:class "font-medium"} text])))

(defn- outline-strip-row [{:keys [start end content priority daypart]}]
  (let [{:keys [strategy]} content]
    [:div {:class "flex items-baseline gap-3 flex-wrap py-1 text-sm border-t first:border-0"}
     [:span {:class "font-mono text-xs text-muted-foreground w-28 shrink-0"} (str start "–" end)]
     [outline-content content]
     (when (seq daypart)
       [:span {:class "text-xs text-muted-foreground border rounded px-1"} daypart])
     (when (seq strategy) [:span {:class "text-xs text-muted-foreground"} strategy])
     (when priority [:span {:class "text-xs text-muted-foreground"} (str "priority " priority)])]))

(defn- outline-strip-groups
  "Strips grouped by day pattern and ordered for display: groups in
  `days-sort-key` order, each group's rows sorted along the broadcast day.
  Returns a seq of [days-value sorted-strips]."
  [strips broadcast-start]
  (->> strips
       (group-by (comp days-group-key :days))
       (sort-by (fn [[_ [s & _]]] (days-sort-key (:days s))))
       (map (fn [[_ ss]]
              [(:days (first ss))
               (sort-by #(broadcast-minutes (:start %) broadcast-start) ss)]))))

(defn- plan-card-header
  "Header row shared by the outline and overrides cards: title, period
  selector, then any status bits and a Refresh button."
  [title selector & extras]
  [:div {:class "flex items-center justify-between gap-2 flex-wrap"}
   [:div {:class "flex items-center gap-2 flex-wrap"}
    [section-label title]
    selector]
   (into [:div {:class "flex items-center gap-2 flex-wrap"}] extras)])

(defn- channel-quarterly-outline [_]
  (let [quarter (r/atom (first (quarter-options)))]
    (fn [channel]
      (let [slug (channel-guidance-slug channel)
            q    @quarter
            grid @(rf/subscribe [::subs/channel-grid slug (periods/quarter-key q)])
            refresh #(rf/dispatch [::events/reload-channel-grid slug q])]
        (when (and slug (nil? grid))
          (rf/dispatch [::events/load-channel-grid slug q]))
        [card {}
         [card-content {:class "pt-6 space-y-3"}
          [plan-card-header "Quarterly outline"
           [period-select (quarter-options) (:key q) #(reset! quarter %)]
           (when (map? grid)
             [:span {:class "text-xs text-muted-foreground"}
              (str "v" (:version grid)
                   (when-let [c (:created-at grid)]
                     (str " · frozen " (subs (str c) 0 (min 10 (count (str c)))))))])
           (when-let [fs (and (map? grid) (get-in grid [:feasibility :overall_status]))]
             [feasibility-badge fs])
           (when slug
             [button {:size :sm :variant :ghost :on-click refresh} "Refresh"])]
          (cond
            (nil? slug)
            [:p {:class "text-sm text-muted-foreground"}
             "This channel has no name to key its schedule by."]

            (or (nil? grid) (= grid :loading))
            [:p {:class "text-sm text-muted-foreground"} "Loading outline…"]

            (false? grid)
            [:p {:class "text-sm text-muted-foreground"}
             (str "No grid frozen for " (:label q)
                  " yet. Generate one in the Schedule pipeline above.")]

            :else
            (let [blocks (get-in grid [:grid :skeleton :blocks])
                  strips (get-in grid [:grid :strips])
                  notes  (get-in grid [:feasibility :notes])]
              [:div {:class "space-y-4"}
               [:div
                [:h3 {:class "text-xs font-semibold text-muted-foreground mb-1"} "Dayparts"]
                (if (seq blocks)
                  (for [[i b] (map-indexed vector blocks)]
                    ^{:key i} [outline-daypart-row b])
                  [:p {:class "text-sm text-muted-foreground"} "No daypart skeleton stored."])]
               [:div
                [:h3 {:class "text-xs font-semibold text-muted-foreground mb-1"} "Rotation strips"]
                (if (seq strips)
                  (let [bstart (get-in grid [:grid :broadcast_day_start])]
                    (for [[days ss] (outline-strip-groups strips bstart)]
                      ^{:key (str (days-group-key days))}
                      [:div {:class "mb-3 last:mb-0"}
                       [:div {:class "text-xs font-medium text-foreground mb-0.5"}
                        (outline-days-label days)]
                       (for [s ss]
                         ^{:key (:strip_id s)} [outline-strip-row s])]))
                  [:p {:class "text-sm text-muted-foreground"} "No rotation strips stored."])]
               (when (seq notes)
                 [:div
                  [:h3 {:class "text-xs font-semibold text-muted-foreground mb-1"} "Feasibility notes"]
                  (for [[i n] (map-indexed vector notes)]
                    ^{:key i} [:p {:class "text-xs text-muted-foreground"} n])])]))]]))))

;; ── Monthly overrides ───────────────────────────────────────────────────────
;; Read-only view of the date-bounded exceptions layered over the quarterly
;; grid for one month (each plays in place of the strips it overlaps).

(defn- override-when
  "Human-readable scope: a single date, or a day pattern with its window."
  [{:keys [date days effective_start effective_end]}]
  (if date
    (.toLocaleDateString (js/Date. (str date "T00:00:00")) js/undefined
                         #js {:weekday "short" :month "short" :day "numeric"})
    (str (outline-days-label (or days "daily"))
         (when (or effective_start effective_end)
           (str " · " (or effective_start "…") " → " (or effective_end "…"))))))

(defn- override-sort-key [{:keys [scope start]}]
  [(or (:date scope) (:effective_start scope) "") (or start "")])

(defn- override-row [{:keys [scope start end content priority note]}]
  [:div {:class "py-1.5 text-sm border-t first:border-0 space-y-0.5"}
   [:div {:class "flex items-baseline gap-3 flex-wrap"}
    [:span {:class "text-xs text-muted-foreground w-40 shrink-0"} (override-when scope)]
    [:span {:class "font-mono text-xs text-muted-foreground"} (str start "–" end)]
    [outline-content content]
    (when (and priority (not (zero? priority)))
      [:span {:class "text-xs text-muted-foreground"} (str "priority " priority)])]
   (when (seq note)
     [:p {:class "text-xs text-muted-foreground"} note])])

(defn- channel-monthly-overrides [_]
  (let [month (r/atom (first (month-options)))]
    (fn [channel]
      (let [slug (channel-guidance-slug channel)
            m    @month
            rec  @(rf/subscribe [::subs/channel-overrides slug (:month m)])
            refresh #(rf/dispatch [::events/reload-channel-overrides slug (:month m)])]
        (when (and slug (nil? rec))
          (rf/dispatch [::events/load-channel-overrides slug (:month m)]))
        [card {}
         [card-content {:class "pt-6 space-y-3"}
          [plan-card-header "Monthly overrides"
           [period-select (month-options) (:key m) #(reset! month %)]
           (when (map? rec)
             [:span {:class "text-xs text-muted-foreground"} (str "v" (:version rec))])
           (when slug
             [button {:size :sm :variant :ghost :on-click refresh} "Refresh"])]
          (cond
            (nil? slug)
            [:p {:class "text-sm text-muted-foreground"}
             "This channel has no name to key its schedule by."]

            (or (nil? rec) (= rec :loading))
            [:p {:class "text-sm text-muted-foreground"} "Loading overrides…"]

            (false? rec)
            [:p {:class "text-sm text-muted-foreground"}
             (str "No overrides generated for " (:label m)
                  ". Generate them in the Schedule pipeline above (needs that month's quarterly grid).")]

            (empty? (:overrides rec))
            [:p {:class "text-sm text-muted-foreground"}
             (str "No overrides for " (:label m) ": every day follows the quarterly grid.")]

            :else
            [:div
             (for [o (sort-by override-sort-key (:overrides rec))]
               ^{:key (:override_id o)} [override-row o])])]]))))

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
       [ffmpeg-profile-selector channel]
       (when (seq channels)
         [:select {:class     select-class
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

     ;; Keyed by channel so the period selectors and edit state reset when
     ;; switching channels.
     (when channel
       ^{:key (str "pipeline-" (:id channel))} [channel-pipeline-card channel playout-job])

     (when channel
       ^{:key (str "guidance-" (:id channel))} [channel-guidance-card channel])

     (when channel
       ^{:key (str "outline-" (:id channel))} [channel-quarterly-outline channel])

     (when channel
       ^{:key (str "overrides-" (:id channel))} [channel-monthly-overrides channel])

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
