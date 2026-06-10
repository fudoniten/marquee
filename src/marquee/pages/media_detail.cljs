(ns marquee.pages.media-detail
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content]]))

(defn- key-name
  "Short name of a map key, ignoring keyword namespaces. Tunarr Scheduler
   responses use namespaced keys like :tunarr.scheduler.media/tags."
  [k]
  (if (keyword? k) (name k) (str k)))

(defn- field-by-name
  "Look up a value in a map by key name, ignoring keyword namespaces."
  [m k]
  (some (fn [[mk v]] (when (= (key-name mk) k) v)) m))

(defn- humanize [k]
  (-> (key-name k)
      (str/replace "-" " ")
      (str/replace "_" " ")
      str/capitalize))

(def ^:private iso-timestamp-re
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*")

(defn- display-str [v]
  (cond
    (keyword? v)
    (name v)

    ;; Render ISO timestamps in the viewer's locale.
    (and (string? v) (re-matches iso-timestamp-re v))
    (let [d (js/Date. v)]
      (if (js/isNaN (.getTime d)) v (.toLocaleString d)))

    (string? v)
    v

    :else
    (str v)))

(defn- blank-value? [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))
      (and (coll? v) (empty? v))))

(defn chips [values]
  [:div {:class "flex flex-wrap gap-1.5"}
   (for [[i v] (map-indexed vector values)]
     ^{:key i}
     [:span {:class "inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground"}
      (display-str v)])])

(declare render-value)

(defn- nested-rows
  "Render a nested map as label/value lines, e.g. process timestamps."
  [m]
  [:div {:class "space-y-1"}
   (for [[k v] (sort-by (comp key-name first) m)]
     ^{:key (key-name k)}
     [:div {:class "flex flex-wrap gap-x-2 text-sm"}
      [:span {:class "text-muted-foreground"} (str (humanize k) ":")]
      [render-value v]])])

(defn render-value [v]
  (cond
    (map? v)        [nested-rows v]
    (sequential? v) (if (every? map? v)
                      [:div {:class "space-y-2"}
                       (for [[i m] (map-indexed vector v)]
                         ^{:key i} [nested-rows m])]
                      [chips v])
    (set? v)        [chips (sort-by display-str v)]
    :else           [:span {:class "break-all"} (display-str v)]))

(defn field-row [label value]
  (when-not (blank-value? value)
    [:div {:class "py-2 grid grid-cols-3 gap-4 border-b border-border/50 last:border-0"}
     [:dt {:class "text-sm font-medium text-muted-foreground"} label]
     [:dd {:class "text-sm col-span-2"} [render-value value]]]))

(defn chip-list [label values]
  (when (seq values)
    [:div {:class "py-2"}
     [:p {:class "text-sm font-medium text-muted-foreground mb-1.5"} label]
     [chips values]]))

(defn remaining-fields
  "Render any fields not explicitly laid out above, so nothing in the
   response is hidden. `shown` is a set of key names already displayed."
  [m shown]
  (let [extras (->> m
                    (remove (fn [[k v]] (or (shown (key-name k)) (blank-value? v))))
                    (sort-by (comp key-name first)))]
    (when (seq extras)
      [:dl {:class "border-t border-border/50 mt-2 pt-1"}
       (for [[k v] extras]
         ^{:key (key-name k)}
         [field-row (humanize k) v])])))

(defn loading-placeholder []
  [:div {:class "space-y-2 animate-pulse"}
   [:div {:class "h-4 bg-muted rounded w-3/4"}]
   [:div {:class "h-4 bg-muted rounded w-1/2"}]
   [:div {:class "h-4 bg-muted rounded w-2/3"}]])

(defn pseudovision-card [media-item]
  [card {}
   [card-header {}
    [card-title {} "Pseudovision"]
    [card-description {} "Media information from the Pseudovision catalog"]]
   [card-content {}
    (cond
      (nil? media-item)
      [loading-placeholder]

      (not media-item)
      [:p {:class "text-sm text-destructive"}
       "Could not load this item from Pseudovision. It may have been removed, or something has gone wrong upstream."]

      :else
      [:div
       [:dl
        [field-row "Name" (:name media-item)]
        [field-row "Kind" (:kind media-item)]
        [field-row "Year" (:year media-item)]
        [field-row "Release date" (:release-date media-item)]
        [field-row "Content rating" (:content-rating media-item)]
        [field-row "State" (:state media-item)]
        [field-row "Pseudovision ID" (:id media-item)]
        [field-row "Remote key (Jellyfin)" (:remote-key media-item)]]
       (when-let [plot (:plot media-item)]
         [:div {:class "pt-3"}
          [:p {:class "text-sm font-medium text-muted-foreground mb-1"} "Plot"]
          [:p {:class "text-sm leading-relaxed"} plot]])
       [remaining-fields media-item
        #{"name" "kind" "year" "release-date" "content-rating" "state"
          "id" "remote-key" "plot"}]])]])

(defn scheduler-card [scheduler-metadata]
  [card {}
   [card-header {}
    [card-title {} "Tunarr Scheduler"]
    [card-description {} "Tags, genres and scheduling metadata synced from Pseudovision"]]
   [card-content {}
    (cond
      (nil? scheduler-metadata)
      [loading-placeholder]

      (not scheduler-metadata)
      [:p {:class "text-sm text-muted-foreground"}
       "No scheduler metadata yet — this item may not have been synced to Tunarr Scheduler."]

      :else
      [:div
       [:dl
        [field-row "Name" (field-by-name scheduler-metadata "name")]
        [field-row "Type" (field-by-name scheduler-metadata "type")]
        [field-row "Item kind" (field-by-name scheduler-metadata "item-kind")]
        [field-row "Library" (field-by-name scheduler-metadata "library-id")]
        [field-row "Premiere" (field-by-name scheduler-metadata "premiere")]]
       [chip-list "Tags" (field-by-name scheduler-metadata "tags")]
       [chip-list "Genres" (field-by-name scheduler-metadata "genres")]
       [chip-list "Channels" (field-by-name scheduler-metadata "channels")]
       [chip-list "Taglines" (field-by-name scheduler-metadata "taglines")]
       [remaining-fields scheduler-metadata
        #{"name" "type" "item-kind" "library-id" "premiere"
          "tags" "genres" "channels" "taglines"}]])]])

(defn page []
  (let [media-id @(rf/subscribe [::subs/current-media-id])
        media-item @(rf/subscribe [::subs/media-item media-id])
        scheduler-metadata @(rf/subscribe [::subs/scheduler-metadata media-id])]
    [:div {:class "space-y-6"}
     [:div
      [button {:variant :ghost
               :size :sm
               :on-click #(rf/dispatch [::events/navigate :media])}
       "← Back to Media"]]

     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"}
       (or (:name media-item)
           (if (nil? media-item) "Loading…" (str "Media Item #" media-id)))]
      [:div {:class "flex items-center gap-2 mt-2 text-muted-foreground"}
       (when-let [kind (:kind media-item)]
         [:span {:class "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium capitalize"}
          (display-str kind)])
       (when-let [year (:year media-item)]
         [:span {:class "text-sm"} year])
       (when-let [rating (:content-rating media-item)]
         [:span {:class "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium"}
          rating])]]

     [:div {:class "grid gap-6 lg:grid-cols-2"}
      [pseudovision-card media-item]
      [scheduler-card scheduler-metadata]]]))
