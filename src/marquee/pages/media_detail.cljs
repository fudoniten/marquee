(ns marquee.pages.media-detail
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.card :refer [card card-content card-header card-title]]))

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

(defn chips
  "Render values as chips. With `on-click`, chips become buttons and the
   handler is called with the chip's display string."
  ([values] [chips values nil])
  ([values on-click]
   [:div {:class "flex flex-wrap gap-1.5"}
    (for [[i v] (map-indexed vector values)
          :let [label (display-str v)]]
      ^{:key i}
      (if on-click
        [:button {:class "inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground transition-colors hover:bg-secondary/70"
                  :on-click #(on-click label)}
         label]
        [:span {:class "inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground"}
         label]))]))

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

(defn chip-list
  ([label values] [chip-list label values nil])
  ([label values on-click]
   (when (seq values)
     [:div {:class "py-2"}
      [:p {:class "text-sm font-medium text-muted-foreground mb-1.5"} label]
      [chips values on-click]])))

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

(defn- dimension-chips
  "Render dimension categories as clickable chips."
  [categories]
  (when (and (map? categories) (seq categories))
    [:div {:class "py-2"}
     [:p {:class "text-sm font-medium text-muted-foreground mb-1.5"} "Dimensions"]
     [:div {:class "space-y-2"}
      (for [[dim values] categories]
        ^{:key dim}
        [:div {:class "flex flex-wrap items-center gap-1.5"}
         [:span {:class "text-xs font-medium text-muted-foreground mr-1"}
          (str (humanize dim) ":")]
         (for [v values]
           ^{:key v}
           [:button {:class "inline-flex items-center rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary transition-colors hover:bg-primary/20"
                     :on-click #(rf/dispatch [::events/browse-select-item :dimensions (str dim ":" v)])}
            (str v)])])]]))

(def ^:private known-fields
  #{"name" "kind" "type" "year" "release-date" "content-rating" "premiere"
    "state" "id" "remote-key" "plot" "description" "tags" "genres"
    "channels" "taglines" "parent-id" "categories"})

(declare tag-editor)

(defn- curation-card [media-id]
  [card {}
   [card-content {:class "pt-6"}
    [:div {:class "space-y-3"}
     [:div {:class "flex flex-wrap items-center gap-2"}
      [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"} "Curation"]
      [action-btn {:action-key [:retag media-id]
                   :label "Retag"
                   :on-click #(rf/dispatch [::events/trigger-media-item-retag media-id])}]
      [action-btn {:action-key [:recategorize media-id]
                   :label "Recategorize"
                   :on-click #(rf/dispatch [::events/trigger-media-item-recategorize media-id])}]
      [action-btn {:action-key [:sync-pseudovision media-id]
                   :label "Sync Tags"
                   :on-click #(rf/dispatch [::events/trigger-media-item-sync-pseudovision media-id])}]]
     [:div {:class "flex flex-wrap items-center gap-2"}
      [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"} "Reset"]
      [action-btn {:action-key [:reset-process media-id "retag"]
                   :label "Retag"
                   :variant :ghost
                   :size :sm
                   :on-click #(rf/dispatch [::events/trigger-reset-media-item-process media-id "retag"])}]
      [action-btn {:action-key [:reset-process media-id "recategorize"]
                   :label "Recategorize"
                   :variant :ghost
                   :size :sm
                   :on-click #(rf/dispatch [::events/trigger-reset-media-item-process media-id "recategorize"])}]
      [action-btn {:action-key [:reset-process media-id "episode-tagging"]
                   :label "Episode Tags"
                   :variant :ghost
                   :size :sm
                   :on-click #(rf/dispatch [::events/trigger-reset-media-item-process media-id "episode-tagging"])}]]]]])

(defn- detail-card
  "Main metadata card merging Pseudovision + scheduler fields."
  [{:keys [merged remote-key jellyfin-url loading? numeric-id categories]}]
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
        [:div {:class "py-2"}
         [:p {:class "text-sm font-medium text-muted-foreground mb-1.5"} "Tags"]
         [tag-editor numeric-id
          (or (field-by-name merged "tags") (:tags merged))]]
       [chip-list "Taglines" (or (field-by-name merged "taglines") (:taglines merged))]
       [dimension-chips categories]
       [remaining-fields merged known-fields]])]])

;;; ── Add to Collection ───────────────────────────────────────────────────

(defn- add-to-collection-dropdown [media-id]
  (let [open?       @(rf/subscribe [::subs/add-to-collection-open?])
        collections @(rf/subscribe [::subs/collections])]
    [:div {:class "relative inline-block"}
     [button {:size     :sm
              :variant  :outline
              :on-click #(rf/dispatch [::events/toggle-add-to-collection])}
      (if open? "Cancel" "+ Collection")]
     (when open?
       [:div {:class "absolute z-10 mt-1 w-56 rounded-md border bg-popover p-1 shadow-md"}
        (if (empty? collections)
          [:p {:class "px-2 py-1.5 text-sm text-muted-foreground"} "No collections yet."]
          (for [coll (sort-by :name collections)
                :let [already? (some #{(if (number? media-id) media-id (js/parseInt media-id))}
                                     (:items coll))]]
            ^{:key (:id coll)}
            [:button {:class    (str "flex w-full items-center justify-between rounded-sm px-2 py-1.5 text-sm "
                                     (if already?
                                       "text-muted-foreground"
                                       "hover:bg-accent hover:text-accent-foreground cursor-pointer"))
                      :disabled (boolean already?)
                      :on-click #(rf/dispatch [::events/add-to-collection (:id coll) media-id])}
             (:name coll)
             (when already?
                [:span {:class "text-xs"} "✓"])]))])]))

;;; ── Tag Editor ──────────────────────────────────────────────────────────────

(defn- tag-editor [numeric-id scheduler-tags]
  (let [new-tag (r/atom "")]
    (fn [numeric-id scheduler-tags]
      (let [pv-tags   (mapv str @(rf/subscribe [::subs/media-tags numeric-id]))
            sched-tags (mapv str (or scheduler-tags []))
            pv-set    (set pv-tags)
            all-tags  (vec (distinct (concat pv-tags sched-tags)))]
        [:div {:class "space-y-2"}
         (when (seq all-tags)
           [:div {:class "flex flex-wrap gap-1.5"}
            (for [tag all-tags]
              (let [in-pv? (contains? pv-set tag)]
                ^{:key tag}
                [:span {:class (str "inline-flex items-center gap-1 rounded-full pl-2.5 pr-1 py-0.5 text-xs font-medium "
                                    (if in-pv?
                                      "bg-secondary text-secondary-foreground"
                                      "bg-muted text-muted-foreground"))}
                 [:button {:class "hover:text-primary"
                           :on-click #(rf/dispatch [::events/browse-select-item :tags tag])}
                  tag]
                 (when in-pv?
                   [:button {:class "inline-flex items-center justify-center w-4 h-4 rounded-full text-[10px] text-secondary-foreground/60 hover:text-destructive hover:bg-destructive/10 transition-colors ml-0.5"
                             :on-click #(rf/dispatch [::events/remove-media-tag numeric-id tag])}
                    "×"])]))])
         [:div {:class "flex gap-2"}
          [:input {:type "text"
                   :class "flex h-8 rounded-md border border-input bg-background px-2 py-1 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                   :placeholder "Add tag..."
                   :value @new-tag
                   :on-change #(reset! new-tag (.. % -target -value))
                   :on-key-down #(when (= "Enter" (.-key %))
                                    (.preventDefault %)
                                    (let [tag (str/trim @new-tag)]
                                      (when (seq tag)
                                        (rf/dispatch [::events/add-media-tag numeric-id tag])
                                        (reset! new-tag ""))))}]
          [button {:size :sm
                   :variant :outline
                   :disabled (str/blank? @new-tag)
                   :on-click #(let [tag (str/trim @new-tag)]
                                (when (seq tag)
                                  (rf/dispatch [::events/add-media-tag numeric-id tag])
                                  (reset! new-tag "")))}
           "Add"]]]))))

;;; ── Page ────────────────────────────────────────────────────────────────────

(defn page []
  (let [media-id           @(rf/subscribe [::subs/current-media-id])
        media-item         @(rf/subscribe [::subs/media-item media-id])
        scheduler-metadata @(rf/subscribe [::subs/scheduler-metadata media-id])
        categories         @(rf/subscribe [::subs/media-categories media-id])
        jellyfin-url       @(rf/subscribe [::subs/jellyfin-url])
        loading?           (nil? media-item)
        not-found?         (false? media-item)
        remote-key         (:remote-key media-item)
        jf-link            (jellyfin-item-url jellyfin-url remote-key)
        merged             (when (map? media-item)
                             (merge-metadata media-item scheduler-metadata))
        numeric-id         (when (map? media-item) (:id media-item))
        ctx                {:media-id media-id :media-item media-item :merged merged
                            :remote-key remote-key :jf-link jf-link
                            :jellyfin-url jellyfin-url :loading? loading?
                            :numeric-id numeric-id
                            :categories categories}]
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center gap-2"}
      [button {:variant :ghost
               :size :sm
               :on-click #(rf/dispatch [::events/navigate :media])}
       "← Back to Media"]
      [:div {:class "flex-1"}]
      (when (and media-item (not not-found?))
        [add-to-collection-dropdown media-id])]

     (when not-found?
       [:div {:class "rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive"}
        "This media item could not be found. It may have been removed, or something has gone wrong upstream."])

     (when-not not-found?
       [:<>
        [hero-section ctx]
        [detail-card ctx]
        [curation-card media-id]])]))
