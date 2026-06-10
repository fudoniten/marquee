(ns marquee.pages.schedule
  "Channel schedule views:
   - `grid-page`  — TV-guide-style grid: channels as rows, time as columns
   - `channel-page` — single-channel upcoming schedule as a vertical list"
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content]]))

;; ---------------------------------------------------------------------------
;; Time helpers
;; ---------------------------------------------------------------------------

(defn- now-ms [] (.getTime (js/Date.)))

(defn- format-time [ms]
  (let [d (js/Date. ms)]
    (str (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit" :hour12 false}))))

(defn- format-date-time [ms]
  (let [d (js/Date. ms)]
    (str (.toLocaleDateString d js/undefined #js {:weekday "short" :month "short" :day "numeric"})
         " "
         (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit" :hour12 false}))))

(defn- duration-str [start-ms end-ms]
  (let [mins (js/Math.round (/ (- end-ms start-ms) 60000))]
    (if (>= mins 60)
      (str (js/Math.floor (/ mins 60)) "h " (mod mins 60) "m")
      (str mins "m"))))

;; ---------------------------------------------------------------------------
;; Grid page  (multi-channel guide)
;; ---------------------------------------------------------------------------

(def ^:private grid-window-ms (* 2 60 60 1000)) ; 2-hour window
(def ^:private px-per-min 3)                    ; pixels per minute in the grid

(defn- slot-style [slot-start slot-end window-start window-end]
  (let [clamped-start (max slot-start window-start)
        clamped-end   (min slot-end   window-end)
        left-min      (/ (- clamped-start window-start) 60000)
        width-min     (/ (- clamped-end clamped-start) 60000)]
    {:left  (str (* left-min px-per-min) "px")
     :width (str (max 2 (* width-min px-per-min)) "px")}))

(defn- time-ruler [window-start window-end]
  (let [step-ms    (* 30 60 1000)
        total-min  (/ (- window-end window-start) 60000)
        ticks      (range 0 (inc total-min) 30)]
    [:div {:class "relative h-8 border-b bg-muted/30 flex-shrink-0"
           :style {:width (str (* total-min px-per-min) "px")}}
     (for [t ticks]
       ^{:key t}
       [:span {:class "absolute text-xs text-muted-foreground top-1"
               :style {:left (str (* t px-per-min) "px")}}
        (format-time (+ window-start (* t 60000)))])]))

(defn- grid-slot [{:keys [title start-ms end-ms]} window-start window-end on-click]
  (let [style (slot-style start-ms end-ms window-start window-end)
        now   (now-ms)
        past? (< end-ms now)
        live? (and (<= start-ms now) (> end-ms now))]
    [:div {:class    (str "absolute top-1 bottom-1 rounded px-1 overflow-hidden cursor-pointer "
                          "border text-xs flex items-center select-none "
                          (cond
                            live? "bg-primary/20 border-primary text-primary-foreground font-medium"
                            past? "bg-muted/40 border-muted text-muted-foreground opacity-60"
                            :else "bg-card border-border hover:bg-accent"))
          :style     style
          :title     (str title "\n" (format-time start-ms) " – " (format-time end-ms))
          :on-click  #(when on-click (on-click))}
     [:span {:class "truncate"} title]]))

(defn- channel-row [channel window-start window-end slots on-click-slot]
  (let [total-min (/ (- window-end window-start) 60000)]
    [:div {:class "flex border-b" :key (:id channel)}
     ;; Channel label column
     [:div {:class "w-32 flex-shrink-0 flex items-center px-3 py-2 bg-muted/20 border-r text-sm font-medium truncate"}
      (:name channel)]
     ;; Slots row (scrolls with the ruler above)
     [:div {:class "relative flex-1 overflow-hidden"
            :style {:height "44px" :min-width (str (* total-min px-per-min) "px")}}
      (for [slot slots]
        ^{:key (str (:start-ms slot) "-" (:title slot))}
        [grid-slot slot window-start window-end #(on-click-slot channel slot)])]]))

(defn grid-page []
  (let [channels    @(rf/subscribe [::subs/channels])
        grid-data   @(rf/subscribe [::subs/schedule-grid])
        loading?    @(rf/subscribe [::subs/schedule-loading?])
        window-start @(rf/subscribe [::subs/schedule-window-start])
        window-end  (+ window-start grid-window-ms)
        now         (now-ms)]
    [:div {:class "space-y-4"}
     [:div {:class "flex items-center justify-between"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"} "Guide"]
       [:p {:class "text-muted-foreground"} "What's playing across all channels."]]
      [:div {:class "flex items-center gap-2"}
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-back])}
        "← Back"]
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-reset])}
        "Now"]
       [button {:size :sm :variant :outline
                :on-click #(rf/dispatch [::events/schedule-window-forward])}
        "Forward →"]]]

     (cond
       loading?
       [:p {:class "text-muted-foreground"} "Loading schedule…"]

       (empty? channels)
       [:p {:class "text-muted-foreground"} "No channels found."]

       :else
       [:div {:class "border rounded-lg overflow-auto"}
        ;; Sticky channel-label gutter + scrollable time area
        [:div {:class "flex"}
         [:div {:class "w-32 flex-shrink-0"}]          ; spacer above channel labels
         [:div {:class "flex-1 overflow-x-auto"}
          [time-ruler window-start window-end]]]
        ;; Channel rows
        (for [ch channels
              :let [slots (get grid-data (:id ch) [])]]
          ^{:key (:id ch)}
          [channel-row ch window-start window-end slots
           (fn [ch slot]
             (rf/dispatch [::events/navigate-to-channel (:id ch)]))])])]))

;; ---------------------------------------------------------------------------
;; Channel (single) page
;; ---------------------------------------------------------------------------

(defn- schedule-entry [{:keys [title description start-ms end-ms thumbnail-url]}]
  (let [now   (now-ms)
        live? (and (<= start-ms now) (> end-ms now))
        past? (< end-ms now)]
    [:div {:class (str "flex gap-4 py-4 border-b last:border-0 "
                       (when past? "opacity-50"))}
     ;; Thumbnail
     (when thumbnail-url
       [:img {:src     thumbnail-url
              :class   "w-20 h-14 object-cover rounded flex-shrink-0 bg-muted"
              :loading "lazy"
              :on-error #(-> % .-target .-style (aset "display" "none"))}])
     [:div {:class "flex-1 min-w-0"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       (when live?
         [:span {:class "text-xs font-bold text-primary uppercase tracking-wide"} "LIVE"])
       [:span {:class "text-sm font-medium"} title]]
      [:p {:class "text-xs text-muted-foreground mt-0.5"}
       (str (format-date-time start-ms) " · " (duration-str start-ms end-ms))]
      (when (and description (not (empty? description)))
        [:p {:class "text-sm text-muted-foreground mt-1 line-clamp-2"} description])]]))

(defn channel-page []
  (let [channel  @(rf/subscribe [::subs/current-channel])
        schedule @(rf/subscribe [::subs/current-channel-schedule])
        loading? @(rf/subscribe [::subs/channel-schedule-loading?])
        channels @(rf/subscribe [::subs/channels])]
    [:div {:class "space-y-6"}
     ;; Header + channel picker
     [:div {:class "flex items-start justify-between gap-4 flex-wrap"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"}
        (or (:name channel) "Schedule")]
       (when (:description channel)
         [:p {:class "text-muted-foreground"} (:description channel)])]
      (when (seq channels)
        [:select {:class   "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm"
                  :value   (or (:id channel) "")
                  :on-change #(rf/dispatch [::events/navigate-to-channel
                                            (js/parseInt (.. % -target -value))])}
         [:option {:value "" :disabled true} "Select channel…"]
         (for [ch channels]
           ^{:key (:id ch)}
           [:option {:value (:id ch)} (:name ch)])])]

     (cond
       loading?
       [:p {:class "text-muted-foreground"} "Loading schedule…"]

       (nil? channel)
       [:p {:class "text-muted-foreground"} "Select a channel above."]

       (empty? schedule)
       [:p {:class "text-muted-foreground"} "No upcoming items scheduled."]

       :else
       [card {}
        [card-content {:class "p-0 divide-y"}
         (for [entry schedule]
           ^{:key (str (:start-ms entry) "-" (:title entry))}
           [schedule-entry entry])]])]))
