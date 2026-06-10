(ns marquee.pages.media-detail
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-content]]))

;;; ── Utilities ───────────────────────────────────────────────────────────────

(defn- key-name [k]
  (if (keyword? k) (name k) (str k)))

(defn- field-by-name [m k]
  (some (fn [[mk v]] (when (= (key-name mk) k) v)) m))

(defn- humanize [k]
  (-> (key-name k)
      (str/replace "-" " ")
      (str/replace "_" " ")
      str/capitalize))

(def ^:private iso-timestamp-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*")

(defn- display-str [v]
  (cond
    (keyword? v) (name v)
    (and (string? v) (re-matches iso-timestamp-re v))
    (let [d (js/Date. v)]
      (if (js/isNaN (.getTime d)) v (.toLocaleString d)))
    (string? v) v
    :else (str v)))

(defn- blank-value? [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))
      (and (coll? v) (empty? v))))

;;; ── Rendering primitives ────────────────────────────────────────────────────

(defn chips [values]
  [:div {:class "flex flex-wrap gap-1.5"}
   (for [[i v] (map-indexed vector values)]
     ^{:key i}
     [:span {:class "inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground"}
      (display-str v)])])

(declare render-value)

(defn- nested-rows [m]
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
  "Render any fields not in `shown`, so nothing is hidden."
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

;;; ── Jellyfin helpers ────────────────────────────────────────────────────────

(defn- jellyfin-image-url [remote-key]
  (str "/api/jellyfin/Items/" remote-key "/Images/Primary?maxHeight=400&quality=90"))

(defn- jellyfin-item-url [jellyfin-url item-id]
  (when (and jellyfin-url item-id)
    (str jellyfin-url "/web/index.html#!/details?id=" item-id)))

(defn- merge-metadata
  "Merge Pseudovision item and scheduler metadata into a single flat map,
   with Pseudovision fields taking precedence for overlapping keys."
  [media-item scheduler-metadata]
  (let [sched (when (map? scheduler-metadata)
                (->> scheduler-metadata
                     (map (fn [[k v]] [(keyword (key-name k)) v]))
                     (into {})))]
    (merge sched media-item)))

;;; ── Sub-components ──────────────────────────────────────────────────────────

(defn- hero-section
  "Thumbnail poster, title, badges, and Jellyfin link."
  [{:keys [media-id media-item merged remote-key jf-link loading?]}]
  [:div {:class "flex gap-6 items-start"}
   (when remote-key
     [:div {:class "shrink-0 rounded-lg overflow-hidden bg-muted shadow-md" :style {:width "160px"}}
      [:img {:src      (jellyfin-image-url remote-key)
             :alt      (or (:name media-item) "")
             :class    "w-full object-cover"
             :loading  "lazy"
             :on-error #(-> % .-target .-parentElement .-style (aset "display" "none"))}]])
   [:div {:class "flex-1 min-w-0"}
    [:h1 {:class "text-3xl font-bold tracking-tight"}
     (if loading?
       "Loading…"
       (or (:name media-item) (str "Media Item #" media-id)))]
    [:div {:class "flex flex-wrap items-center gap-2 mt-2 text-muted-foreground"}
     (when-let [kind (or (:kind media-item) (and merged (field-by-name merged "type")))]
       [:span {:class "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium capitalize"}
        (display-str kind)])
     (when-let [year (:year media-item)]
       [:span {:class "text-sm"} year])
     (when-let [rating (:content-rating media-item)]
       [:span {:class "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium"}
        rating])]
    (when jf-link
      [:a {:href   jf-link
           :target "_blank"
           :rel    "noopener noreferrer"
           :class  "inline-flex items-center gap-1 mt-3 text-sm text-primary underline-offset-4 hover:underline"}
       "View in Jellyfin ↗"])]])

(defn- parent-field-row
  "Field row for the parent ID, rendered as a Jellyfin link when possible."
  [parent-id jellyfin-url]
  (when-not (blank-value? parent-id)
    [:div {:class "py-2 grid grid-cols-3 gap-4 border-b border-border/50"}
     [:dt {:class "text-sm font-medium text-muted-foreground"} "Parent"]
     [:dd {:class "text-sm col-span-2"}
      (if-let [url (jellyfin-item-url jellyfin-url parent-id)]
        [:a {:href   url
             :target "_blank"
             :rel    "noopener noreferrer"
             :class  "underline underline-offset-2 hover:text-primary"}
         (str parent-id)]
        [:span (str parent-id)])]]))

(def ^:private known-fields
  #{"name" "kind" "type" "year" "release-date" "content-rating" "premiere"
    "state" "id" "remote-key" "plot" "description" "tags" "genres"
    "channels" "taglines" "parent-id"})

(defn- detail-card
  "Main metadata card merging Pseudovision + scheduler fields."
  [{:keys [merged remote-key jellyfin-url loading?]}]
  [card {}
   [card-content {:class "pt-6"}
    (if loading?
      [loading-placeholder]
      [:div
       (when-let [plot (or (:plot merged) (:description merged))]
         [:div {:class "mb-4"}
          [:p {:class "text-sm leading-relaxed text-muted-foreground"} plot]])
       [:dl
        [field-row "Kind"           (or (:kind merged) (field-by-name merged "type"))]
        [field-row "Year"           (:year merged)]
        [field-row "Release date"   (:release-date merged)]
        [field-row "Content rating" (:content-rating merged)]
        [field-row "Premiere"       (field-by-name merged "premiere")]
        [field-row "State"          (:state merged)]
        [field-row "Pseudovision ID" (:id merged)]
        [field-row "Jellyfin ID"    remote-key]
        [parent-field-row (:parent-id merged) jellyfin-url]]
       [chip-list "Tags"     (or (field-by-name merged "tags")     (:tags merged))]
       [chip-list "Genres"   (or (field-by-name merged "genres")   (:genres merged))]
       [chip-list "Channels" (or (field-by-name merged "channels") (:channels merged))]
       [chip-list "Taglines" (or (field-by-name merged "taglines") (:taglines merged))]
       [remaining-fields merged known-fields]])]])

;;; ── Page ────────────────────────────────────────────────────────────────────

(defn page []
  (let [media-id           @(rf/subscribe [::subs/current-media-id])
        media-item         @(rf/subscribe [::subs/media-item media-id])
        scheduler-metadata @(rf/subscribe [::subs/scheduler-metadata media-id])
        jellyfin-url       @(rf/subscribe [::subs/jellyfin-url])
        loading?           (nil? media-item)
        not-found?         (false? media-item)
        remote-key         (:remote-key media-item)
        jf-link            (jellyfin-item-url jellyfin-url remote-key)
        merged             (when (map? media-item)
                             (merge-metadata media-item scheduler-metadata))
        ctx                {:media-id media-id :media-item media-item :merged merged
                            :remote-key remote-key :jf-link jf-link
                            :jellyfin-url jellyfin-url :loading? loading?}]
    [:div {:class "space-y-6"}
     [:div
      [button {:variant :ghost
               :size :sm
               :on-click #(rf/dispatch [::events/navigate :media])}
       "← Back to Media"]]

     (when not-found?
       [:div {:class "rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive"}
        "This media item could not be found. It may have been removed, or something has gone wrong upstream."])

     (when-not not-found?
       [:<>
        [hero-section ctx]
        [detail-card ctx]])]))
