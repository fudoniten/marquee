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
  [{:keys [custom-title media-item-id]} media-items]
  (let [item (get media-items media-item-id)]
    (or (not-empty custom-title)
        (when (map? item) (or (not-empty (utils/media-display-name item media-items))
                              (not-empty (:title item))))
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

(defn- grid-slot [{:keys [title start-ms end-ms]} hue window-start window-end on-click]
  (let [now   (now-ms)
        span  (- window-end window-start)
        cs    (max start-ms window-start)
        ce    (min end-ms   window-end)
        left  (* 100 (/ (- cs window-start) span))
        width (* 100 (/ (- ce cs) span))
        live? (and (<= start-ms now) (> end-ms now))
        past? (< end-ms now)]
    [:div {:class    (str "absolute top-1 bottom-1 rounded-sm px-1.5 overflow-hidden cursor-pointer "
                          "border flex flex-col justify-center leading-tight select-none "
                          "transition-[filter] hover:brightness-125 "
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
          :style    (merge {:left (pct left) :width (pct (max width 0)) :min-width "18px"}
                           (when hue (library-color-style hue past?)))
          :title    (str title "\n" (format-time start-ms) " – " (format-time end-ms))
          :on-click #(when on-click (on-click))}
     (when live?
       [:span {:class "text-[9px] font-bold uppercase tracking-wide text-primary"} "Live"])
     [:span {:class "truncate text-xs"} title]]))

(defn- channel-row [channel window-start window-end events media-items libraries-by-id on-click-channel]
  (let [visible (->> events
                     (filter content-event?)
                     (keep #(event->display % media-items))
                     (filter #(and (< (:start-ms %) window-end)
                                   (> (:end-ms %)  window-start))))]
    [:div {:class "flex border-b border-border last:border-0"}
     [:div {:class    (str label-col " flex-shrink-0 flex items-center px-3 py-2 bg-muted/30 "
                           "border-r border-border text-sm font-medium truncate cursor-pointer "
                           "hover:bg-accent hover:text-accent-foreground")
            :title    (:name channel)
            :on-click on-click-channel}
      (:name channel)]
     [:div {:class "relative flex-1" :style (merge {:height "56px"} gridline-style)}
      [now-line window-start window-end]
      (for [ev visible
            :let [item (get media-items (:media-item-id ev))
                  lib  (item->library item libraries-by-id)
                  hue  (when lib (hue-for (:key lib)))]]
        ^{:key (str (:start-ms ev) "-" (:title ev))}
        ;; Clicking a slot opens the media item it plays; fall back to the
        ;; channel page when there's no item to link to (e.g. filler/offline).
        [grid-slot ev hue window-start window-end
         (if-let [mid (:media-item-id ev)]
           #(rf/dispatch [::events/navigate-to-media-detail mid])
           #(rf/dispatch [::events/navigate-to-channel (:id channel)]))])]]))

(defn- used-libraries
  "Distinct {:key :label} libraries appearing among the guide's content events,
  for the legend."
  [channel-evs media-items libraries-by-id]
  (->> (mapcat val channel-evs)
       (filter content-event?)
       (keep :media-item-id)
       (map #(get media-items %))
       (keep #(item->library % libraries-by-id))
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
           [channel-row ch window-start window-end evs media-items libraries-by-id
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

;; ── Strategic guidance ────────────────────────────────────────────────────────

(def ^:private guidance-textarea-class
  "flex w-full min-h-[5rem] rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")

(defn- channel-guidance-slug
  "Identifier Tunarr Scheduler keys a channel under: the channel's name in the
   lower-cased slug form its scheduling endpoints use (e.g. \"Spectrum\" →
   \"spectrum\"). Shared by guidance, the quarterly/monthly/weekly regeneration
   triggers, and the quarterly outline — all keyed by the same config-key
   identifier. Adjust here if the scheduler ever keys any of these by a
   different field."
  [channel]
  (some-> (:name channel) str/trim str/lower-case not-empty))

(defn- channel-guidance-card
  "View and edit a channel's strategic scheduling guidance — free text fed to
   the scheduler's playout planner. Loads lazily on first render for the channel;
   like the media-detail context editors, the editor is always offered even when
   nothing is stored yet or the load failed."
  [_]
  (let [editing? (r/atom false)
        draft    (r/atom "")]
    (fn [channel]
      (let [slug    (channel-guidance-slug channel)
            wrapper @(rf/subscribe [::subs/channel-guidance slug])
            state   @(rf/subscribe [::subs/action-state [:channel-guidance slug]])
            saving? (= :loading (:status state))
            value   (when (map? wrapper) (:guidance wrapper))]
        ;; Trigger the load once (guarded on nil so this doesn't re-dispatch).
        (when (and slug (nil? wrapper))
          (rf/dispatch [::events/load-channel-guidance slug]))
        (let [start-edit (fn [] (reset! draft (or value "")) (reset! editing? true))
              save       (fn []
                           (rf/dispatch [::events/set-channel-guidance slug (str/trim @draft)])
                           (reset! editing? false))]
          [card {}
           [card-content {:class "pt-6 space-y-2"}
            [:div {:class "flex items-center justify-between gap-2"}
             [:div {:class "flex items-center gap-2"}
              [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"}
               "Scheduling guidance"]
              (case (:status state)
                :loading [:span {:class "text-xs text-muted-foreground"} "Saving…"]
                :success [:span {:class "text-xs text-primary"} "Saved ✓"]
                :error   [:span {:class "text-xs text-destructive"} (or (:message state) "Error")]
                nil)]
             (when (and slug (not @editing?))
               [:div {:class "flex gap-1.5"}
                [button {:size :sm :variant :outline :on-click start-edit}
                 (if (seq value) "Edit" "Add")]
                (when (seq value)
                  [button {:size     :sm
                           :variant  :ghost
                           :disabled saving?
                           :on-click #(rf/dispatch [::events/set-channel-guidance slug ""])}
                   "Clear"])])]
            (cond
              (nil? slug)
              [:p {:class "text-sm text-muted-foreground"}
               "This channel has no name to key guidance by."]

              @editing?
              [:div {:class "space-y-2"}
               [:textarea {:class       guidance-textarea-class
                           :rows        4
                           :placeholder "e.g. Air Seinfeld at least twice a week in the evening (primetime). Keep adult content out of the daytime — after 22:00 only."
                           :value       @draft
                           :disabled    saving?
                           :on-change   #(reset! draft (.. % -target -value))}]
               [:div {:class "flex gap-2"}
                [button {:size :sm :disabled saving? :on-click save} "Save"]
                [button {:size :sm :variant :ghost :on-click #(reset! editing? false)} "Cancel"]]]

              (or (nil? wrapper) (= :loading wrapper))
              [:p {:class "text-sm text-muted-foreground"} "Loading guidance…"]

              (seq value)
              [:p {:class "text-sm whitespace-pre-wrap break-words leading-relaxed"} value]

              :else
              [:p {:class "text-sm text-muted-foreground"}
               "No guidance set. Add strategic guidance to steer how this channel is scheduled."])]])))))

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

(defn- channel-quarterly-outline [_]
  (fn [channel]
    (let [slug (channel-guidance-slug channel)
          grid @(rf/subscribe [::subs/channel-grid slug])]
      (when (and slug (nil? grid))
        (rf/dispatch [::events/load-channel-grid slug]))
      [card {}
       [card-content {:class "pt-6 space-y-3"}
        [:div {:class "flex items-center justify-between gap-2 flex-wrap"}
         [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"}
          "Quarterly outline"]
         (when (map? grid)
           [:div {:class "flex items-center gap-2"}
            [:span {:class "text-xs text-muted-foreground"} (str (:quarter grid) " " (:year grid))]
            (when-let [fs (get-in grid [:feasibility :overall_status])]
              [feasibility-badge fs])
            [button {:size :sm :variant :ghost
                     :on-click #(rf/dispatch [::events/reload-channel-grid slug])}
             "Refresh"]])]
        (cond
          (nil? slug)
          [:p {:class "text-sm text-muted-foreground"}
           "This channel has no name to key its schedule by."]

          (= grid :loading)
          [:p {:class "text-sm text-muted-foreground"} "Loading outline…"]

          (false? grid)
          [:div {:class "space-y-2"}
           [:p {:class "text-sm text-muted-foreground"}
            "No quarterly schedule generated yet. Run Regenerate Quarterly to build one."]
           [button {:size :sm :variant :outline
                    :on-click #(rf/dispatch [::events/reload-channel-grid slug])}
            "Check again"]]

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
                  ^{:key i} [:p {:class "text-xs text-muted-foreground"} n])])]))]])))

(defn channel-page []
  (let [channel     @(rf/subscribe [::subs/current-channel])
        raw-events  @(rf/subscribe [::subs/current-channel-events])
        media-items @(rf/subscribe [::subs/media-items-map])
        loading?    @(rf/subscribe [::subs/channel-events-loading?])
        channels    @(rf/subscribe [::subs/channels])
        pv-url      @(rf/subscribe [::subs/pseudovision-url])
        playout-job @(rf/subscribe [::subs/channel-playout-job (:id channel)])
        stream-url  (channel-stream-url pv-url (:uuid channel))
        slug        (channel-guidance-slug channel)
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
       ;; The generation pipeline, in order: quarterly → monthly → weekly →
       ;; playout. Each stage can be re-run independently on demand.
       (when slug
         [action-btn {:action-key [:regenerate-quarterly slug]
                      :label      "Regenerate Quarterly"
                      :on-click   #(rf/dispatch [::events/trigger-regenerate-quarterly slug])}])
       (when slug
         [action-btn {:action-key [:regenerate-monthly slug]
                      :label      "Regenerate Monthly"
                      :on-click   #(rf/dispatch [::events/trigger-regenerate-monthly slug])}])
       (when slug
         [action-btn {:action-key [:regenerate-weekly slug]
                      :label      "Regenerate Weekly"
                      :on-click   #(rf/dispatch [::events/trigger-regenerate-weekly slug])}])
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

     (when channel
       [channel-guidance-card channel])

     (when channel
       [channel-quarterly-outline channel])

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
